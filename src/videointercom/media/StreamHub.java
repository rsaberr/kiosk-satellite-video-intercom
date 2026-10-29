// SPDX-License-Identifier: Apache-2.0
package videointercom.media;

import java.util.Collections;

/**
 * One live camera stream over RTSP (H.264 video + AAC audio) for the length of a ring. It starts as soon as the button
 * is pressed; the FrameHub keeps the frames since the last keyframe for a viewer that attaches later.
 */
public final class StreamHub implements MediaStream {
    private final String host, path, username, password;
    private final int port;
    private final Listener listener;
    private final FrameHub hub = new FrameHub();
    private RtspSession session;
    private Thread starter;
    private volatile boolean running;
    private long videoBase = -1, audioBase = -1;
    private int videoClock = 90000;

    public StreamHub(String host, int port, String path, String username, String password, Listener listener) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.username = username;
        this.password = password;
        this.listener = listener;
    }

    @Override public synchronized void start() {
        if (running) return;
        running = true;
        hub.start();
        Thread thread = new Thread(this::connect, "vi-stream-start");
        thread.setDaemon(true);
        starter = thread;
        thread.start();
    }

    @Override public void stop() {
        RtspSession s;
        Thread t;
        synchronized (this) {
            running = false;
            s = session;
            session = null;
            t = starter;
            starter = null;
        }
        hub.stop();
        if (t != null) t.interrupt();
        if (s != null) s.close();
    }

    @Override public long firstKeyframeMillis() {
        return hub.firstKeyframeMillis();
    }

    @Override public void attachVideo(VideoSink sink) {
        hub.attachVideo(sink);
    }

    @Override public void attachAudio(AudioSink sink) {
        hub.attachAudio(sink);
    }

    private void connect() {
        RtspSession s = new RtspSession(host, port, path, username, password);
        try {
            listener.onStreamState("connecting");
            Sdp sdp = s.describe(Collections.<String, String>emptyMap());
            Sdp.Track videoTrack = sdp.receive("video");
            Sdp.Track audioTrack = sdp.receive("audio");
            if (videoTrack == null) throw new java.io.IOException("the other kiosk offers no video track");
            if (videoTrack.clock > 0) videoClock = videoTrack.clock;
            H264Depacketizer h264 = new H264Depacketizer(this::onAccessUnit);
            int videoChannel = s.setup(videoTrack, 0, Collections.<String, String>emptyMap());
            AacDepacketizer aac = null;
            int audioChannel = -1;
            if (audioTrack != null && audioTrack.codec.equals("MPEG4-GENERIC")) {
                hub.audioFormat(audioTrack.audioConfig(), audioTrack.clock > 0 ? audioTrack.clock : 16000);
                aac = new AacDepacketizer(this::onAacFrame, audioTrack.fmtpInt("sizelength", 13), audioTrack.fmtpInt("indexlength", 3));
                audioChannel = s.setup(audioTrack, 2, Collections.<String, String>emptyMap());
            }
            s.play(Collections.<String, String>emptyMap());
            final AacDepacketizer audioDepacketizer = aac;
            final int vc = videoChannel, ac = audioChannel;
            synchronized (this) {
                if (!running) {
                    s.close();
                    return;
                }
                session = s;
            }
            listener.onStreamState("streaming");
            s.start(new RtspSession.Listener() {
                @Override public void onPacket(int channel, byte[] buffer, int offset, int length) {
                    if (channel == vc) h264.onRtp(buffer, offset, length);
                    else if (channel == ac && audioDepacketizer != null) audioDepacketizer.onRtp(buffer, offset, length);
                }

                @Override public void onClosed(String reason) {
                    if (reason != null && running) listener.onStreamState("stream ended: " + reason);
                }
            }, "vi-stream");
        } catch (Throwable error) {
            s.close();
            if (running) listener.onStreamState("stream failed: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private void onAccessUnit(byte[] annexB, long rtpTimestamp, boolean keyframe) {
        long micros;
        synchronized (this) {
            if (videoBase < 0) {
                if (!keyframe) return;  // nothing decodes before the first keyframe
                videoBase = rtpTimestamp;
            }
            micros = ((rtpTimestamp - videoBase) & 0xFFFFFFFFL) * 1_000_000L / videoClock;
        }
        hub.video(annexB, micros, keyframe);
    }

    private void onAacFrame(byte[] frame, long rtpTimestamp) {
        long micros;
        synchronized (this) {
            if (audioBase < 0) audioBase = rtpTimestamp;
            micros = ((rtpTimestamp - audioBase) & 0xFFFFFFFFL) * 1_000_000L / Math.max(1, hub.audioRate());
        }
        hub.audio(frame, micros);
    }
}
