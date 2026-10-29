// SPDX-License-Identifier: Apache-2.0
package videointercom.media;

import java.util.ArrayList;
import java.util.List;

/**
 * What a stream does with its frames, whatever brings them in: keeps the frames since the last keyframe and replays
 * them to a video sink that attaches later, so the viewer shows a picture at once instead of waiting for the next
 * keyframe, and passes audio on with its format. Times are microseconds from the stream's first frame.
 */
public final class FrameHub {
    private static final int MAX_CACHED_FRAMES = 150;
    private static final int MAX_CACHED_BYTES = 12 * 1024 * 1024;

    private final List<byte[]> gop = new ArrayList<>();
    private final List<Long> gopTimes = new ArrayList<>();
    private int gopBytes;
    private MediaStream.VideoSink video;
    private MediaStream.AudioSink audio;
    private byte[] audioConfig = new byte[0];
    private int audioRate;
    private boolean running;
    private long startedAt;
    private volatile long firstKeyframeMillis = -1;
    private boolean keyframeSeen;

    public synchronized void start() {
        running = true;
        startedAt = System.currentTimeMillis();
    }

    public synchronized void stop() {
        running = false;
        gop.clear();
        gopTimes.clear();
        gopBytes = 0;
    }

    public long firstKeyframeMillis() {
        return firstKeyframeMillis;
    }

    public synchronized void attachVideo(MediaStream.VideoSink sink) {
        video = sink;
        if (sink == null) return;
        for (int i = 0; i < gop.size(); i++) sink.onVideo(gop.get(i), gopTimes.get(i), i == 0);
    }

    public synchronized void attachAudio(MediaStream.AudioSink sink) {
        audio = sink;
        if (sink != null && audioRate > 0) sink.onAudioFormat(audioConfig, audioRate);
    }

    /** One access unit; frames before the first keyframe are dropped, since nothing decodes without it. */
    public synchronized void video(byte[] annexB, long micros, boolean keyframe) {
        if (!running) return;
        if (!keyframeSeen) {
            if (!keyframe) return;
            keyframeSeen = true;
            firstKeyframeMillis = System.currentTimeMillis() - startedAt;
        }
        if (keyframe) {
            gop.clear();
            gopTimes.clear();
            gopBytes = 0;
        }
        if (gop.size() < MAX_CACHED_FRAMES && gopBytes + annexB.length < MAX_CACHED_BYTES) {
            gop.add(annexB);
            gopTimes.add(micros);
            gopBytes += annexB.length;
        }
        // Delivered under the lock, so a sink attaching meanwhile gets each frame exactly once.
        if (video != null) video.onVideo(annexB, micros, keyframe);
    }

    public synchronized void audioFormat(byte[] config, int rate) {
        audioConfig = config;
        audioRate = rate;
        if (audio != null) audio.onAudioFormat(config, rate);
    }

    public synchronized int audioRate() {
        return audioRate;
    }

    public synchronized void audio(byte[] aacFrame, long micros) {
        if (!running) return;
        if (audio != null) audio.onAudio(aacFrame, micros);
    }
}
