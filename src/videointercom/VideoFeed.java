// SPDX-License-Identifier: Apache-2.0
package videointercom;

import android.view.Surface;
import java.util.List;
import videointercom.media.StreamHub;

/**
 * One kiosk's camera stream on one surface: the RTSP stream (StreamHub) and the hardware decoder (VideoRenderer),
 * joined once both exist. Kiosk Satellite serves RTSP on 8554, or on the ONVIF port (8080) when ONVIF is the chosen
 * protocol, so a refused connection moves on to the next port. The surface can change while the stream keeps going,
 * as when the preview screen gives way to the call screen.
 */
final class VideoFeed {
    interface Listener {
        /** Connecting, or what went wrong; null once there is a picture. */
        void onMessage(String message);
        void onSize(int width, int height);
        void log(String line);
    }

    private final String host, path, user, password;
    private final List<Integer> ports;
    private final Listener listener;
    private int portIndex;
    private boolean running;
    private StreamHub stream;
    private VideoRenderer renderer;
    private Surface surface;

    VideoFeed(String host, List<Integer> ports, String path, String user, String password, Listener listener) {
        this.host = host;
        this.ports = ports;
        this.path = path;
        this.user = user;
        this.password = password;
        this.listener = listener;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        open();
    }

    void stop() {
        StreamHub st;
        VideoRenderer r;
        synchronized (this) {
            running = false;
            st = stream;
            stream = null;
            r = renderer;
            renderer = null;
        }
        if (st != null) {
            st.attachVideo(null);
            st.stop();
        }
        if (r != null) r.release();
    }

    /** Shows the stream on this surface from now on; the decoder of the previous one is released first. */
    void setSurface(Surface next) {
        VideoRenderer old;
        synchronized (this) {
            if (surface == next) return;
            old = detach();
            surface = next;
        }
        if (old != null) old.release();
        synchronized (this) {
            attach();
        }
    }

    /** The surface went away; only if it is still the current one. */
    void clearSurface(Surface gone) {
        VideoRenderer old;
        synchronized (this) {
            if (surface != gone) return;
            old = detach();
            surface = null;
        }
        if (old != null) old.release();
    }

    private VideoRenderer detach() {
        VideoRenderer old = renderer;
        renderer = null;
        if (old != null && stream != null) stream.attachVideo(null);
        return old;
    }

    private void open() {
        int port = ports.get(portIndex);
        listener.onMessage("Connecting video…");
        listener.log("video: connecting to " + host + ":" + port + path);
        StreamHub[] self = new StreamHub[1];
        StreamHub hub = new StreamHub(host, port, path, user, password, state -> onState(self[0], state));
        self[0] = hub;
        stream = hub;
        hub.start();
        // A decoder kept from a refused port has had no frames yet, so it simply moves to the new stream.
        if (renderer != null) hub.attachVideo(renderer);
        else attach();
    }

    private void attach() {
        if (!running || stream == null || surface == null || renderer != null) return;
        renderer = new VideoRenderer(surface, new VideoRenderer.Listener() {
            @Override public void onVideoSize(int width, int height) {
                listener.onSize(width, height);
            }

            @Override public void onVideoStats(long firstFrameMillis, double decodeMillis) {
                if (firstFrameMillis >= 0) {
                    listener.onMessage(null);
                    listener.log("video: first picture from " + host + " after " + firstFrameMillis + " ms");
                }
            }

            @Override public void onVideoError(String message) {
                listener.onMessage(message == null ? "No picture yet. Is camera streaming on there?" : "No video: " + message);
            }

            @Override public void onVideoLog(String message) {
                listener.log(message);
            }
        });
        stream.attachVideo(renderer);
    }

    private void onState(StreamHub from, String state) {
        StreamHub failed = null;
        synchronized (this) {
            if (!running || from != stream) return;
            boolean refused = state.startsWith("stream failed");
            if (refused && portIndex + 1 < ports.size()) {
                listener.log("video: " + state + ", trying the next port");
                failed = stream;
                failed.attachVideo(null);
                portIndex++;
                open();
            } else if (state.startsWith("stream failed") || state.startsWith("stream ended")) {
                listener.onMessage("No video: " + state.replaceFirst("^stream (failed|ended): ", ""));
                listener.log("video: " + state);
            }
        }
        if (failed != null) failed.stop();
    }
}
