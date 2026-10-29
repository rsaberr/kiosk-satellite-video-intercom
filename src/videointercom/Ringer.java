// SPDX-License-Identifier: Apache-2.0
package videointercom;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * A telephone ring made on the spot, so the plugin needs no sound files: two seconds of 440 + 480 Hz, then four of
 * silence, again until stopped. Plays as a ringtone, so it follows the ring volume.
 */
final class Ringer {
    private static final int RATE = 16000;
    private final Thread thread;
    private volatile boolean running = true;

    Ringer(int volumePercent) {
        float gain = Math.max(0, Math.min(100, volumePercent)) / 100f;
        thread = new Thread(() -> run(gain), "vi-ringer");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        thread.interrupt();
    }

    private void run(float gain) {
        short[] ring = new short[RATE * 2];
        for (int i = 0; i < ring.length; i++) {
            double t = (double) i / RATE;
            double fade = Math.min(1, Math.min(i, ring.length - i) / (RATE * 0.02));  // no clicks at the edges
            ring[i] = (short) Math.round(fade * 0.4 * 32767 * (Math.sin(2 * Math.PI * 440 * t) + Math.sin(2 * Math.PI * 480 * t)) / 2);
        }
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(ring.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
            track.write(ring, 0, ring.length);
            track.setVolume(gain);
            while (running) {
                track.stop();
                track.reloadStaticData();
                track.play();
                Thread.sleep(6000);
            }
        } catch (InterruptedException stopped) {
            // Stopped.
        } catch (Throwable unavailable) {
            // No ring on this device; the call screen still shows.
        } finally {
            if (track != null) {
                try { track.stop(); } catch (RuntimeException ignored) {}
                track.release();
            }
        }
    }
}
