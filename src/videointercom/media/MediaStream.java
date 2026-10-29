// SPDX-License-Identifier: Apache-2.0
package videointercom.media;

/**
 * One live camera stream (H.264 video + AAC audio) for the length of a ring, over RTSP (StreamHub) or Baichuan.
 * Sinks are called on the stream's reader thread and must hand the work off quickly.
 */
public interface MediaStream {
    interface VideoSink {
        void onVideo(byte[] annexB, long presentationMicros, boolean keyframe);
    }

    interface AudioSink {
        /** AudioSpecificConfig and sample rate, before the first frame. */
        void onAudioFormat(byte[] config, int sampleRate);
        void onAudio(byte[] aacFrame, long presentationMicros);
    }

    interface Listener {
        /** "connecting", "streaming", "stream failed: ..." or "stream ended: ...". */
        void onStreamState(String state);
    }

    /** Connects on its own thread and returns at once. */
    void start();

    void stop();

    /** Attaches the decoder; it first gets the frames since the last keyframe, then live frames. */
    void attachVideo(VideoSink sink);

    void attachAudio(AudioSink sink);

    /** Milliseconds from start to the first keyframe, or -1 before it. */
    long firstKeyframeMillis();
}
