// SPDX-License-Identifier: Apache-2.0
package videointercom;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The other kiosk's voice on this kiosk's speaker: 16 kHz mono PCM played as voice-call audio, so Android's echo
 * canceller has it as its reference. A small jitter buffer as in the Reolink plugin's AudioRenderer: playback starts
 * with START_MILLIS of silence, rebuilds that cushion when it runs dry, and skips audio when more than MAX_MILLIS
 * piles up, so the delay stays bounded.
 */
final class PcmPlayer {
    static final int RATE = 16000;
    private static final int START_MILLIS = 80;
    private static final int MAX_MILLIS = 250;

    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(32);
    private final Thread thread;
    private volatile boolean running = true;
    private volatile float volume;

    PcmPlayer(int volumePercent) {
        volume = Math.max(0, Math.min(100, volumePercent)) / 100f;
        thread = new Thread(this::run, "vi-player");
        thread.setDaemon(true);
        thread.start();
    }

    /** 16-bit little-endian samples from the link. */
    void write(byte[] pcm) {
        if (!running || pcm.length == 0 || (pcm.length & 1) != 0) return;
        if (!queue.offer(pcm)) {
            queue.clear();  // behind: skip ahead rather than delay
            queue.offer(pcm);
        }
    }

    void setVolume(int percent) {
        volume = Math.max(0, Math.min(100, percent)) / 100f;
    }

    void release() {
        running = false;
        thread.interrupt();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        AudioTrack track = null;
        try {
            int minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(minimum, RATE * 2 * MAX_MILLIS / 1000))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
            // Android 12+ only starts a streaming track once its buffer is full; ours never fills that far on purpose.
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    track.setStartThresholdInFrames(Math.min(RATE * START_MILLIS / 1000, track.getBufferSizeInFrames()));
                } catch (RuntimeException refused) {
                    // Keeps the default threshold.
                }
            }
            float applied = -1;
            track.play();
            byte[] cushion = new byte[RATE * 2 * START_MILLIS / 1000];
            track.write(cushion, 0, cushion.length);
            long written = cushion.length / 2;
            while (running) {
                byte[] pcm = queue.poll(20, TimeUnit.MILLISECONDS);
                if (volume != applied) {
                    applied = volume;
                    track.setVolume(applied);
                }
                if (pcm == null) continue;
                long buffered = written - (track.getPlaybackHeadPosition() & 0xFFFFFFFFL);
                if (buffered * 1000 / RATE > MAX_MILLIS) continue;  // too far behind: skip this piece
                if (buffered <= 0) {
                    track.write(cushion, 0, cushion.length);
                    written += cushion.length / 2;
                }
                track.write(pcm, 0, pcm.length);
                written += pcm.length / 2;
            }
        } catch (InterruptedException stopped) {
            // Released.
        } catch (Throwable error) {
            // Nothing to play on; the call goes on without sound here.
        } finally {
            if (track != null) {
                try { track.stop(); } catch (RuntimeException ignored) {}
                track.release();
            }
        }
    }
}
