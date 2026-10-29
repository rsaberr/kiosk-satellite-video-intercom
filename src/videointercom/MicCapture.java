// SPDX-License-Identifier: Apache-2.0
package videointercom;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Build;
import videointercom.Downsampler;
import videointercom.TalkChannel;

/**
 * The kiosk microphone into the call, 10 ms at a time. Adapted from the Reolink Doorbell Intercom plugin. The source
 * is picked once per call and kept: the one from the Microphone setting, or in Automatic the first that opens, Voice
 * communication first since that is where Android's echo canceller works best in call mode. Some panels (an RK3576
 * one) only deliver audio on Camcorder; pick it in the setting there. Automatic tries 16, then 48, then 8 kHz and
 * converts to the call's 16 kHz.
 */
final class MicCapture {
    interface Listener {
        void onMicState(String state);
    }

    /** The Microphone setting's options, in Automatic's order. */
    static final String[] CHOICES = {"Voice communication", "Microphone", "Camcorder", "Voice recognition", "Unprocessed"};
    private static final int[] SOURCES = {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.CAMCORDER,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        Build.VERSION.SDK_INT >= 24 ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.DEFAULT,
    };
    /** The Microphone sample rate setting's options after Automatic, and Automatic's order. */
    static final String[] RATE_CHOICES = {"16000 Hz", "48000 Hz", "8000 Hz"};
    private static final int[] RATES = {16000, 48000, 8000};

    private final TalkChannel talk;
    private final Listener listener;
    private final String choice;
    private final int[] rates;
    private volatile boolean running = true;
    private final Thread thread;

    /** choice is the Microphone setting (one of CHOICES, or Automatic), rateChoice the sample rate one (one of RATE_CHOICES, or Automatic). */
    MicCapture(TalkChannel talk, String choice, String rateChoice, Listener listener) {
        this.talk = talk;
        this.choice = choice;
        this.rates = rates(rateChoice);
        this.listener = listener;
        thread = new Thread(this::run, "vi-mic");
        thread.setDaemon(true);
        thread.start();
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
        AudioRecord recorder = null;
        int rate = 0;
        String opened = null;
        for (int i = 0; i < SOURCES.length && recorder == null; i++) {
            if (!choice.equals("Automatic") && !choice.equals(CHOICES[i])) continue;
            for (int r : rates) {
                recorder = open(SOURCES[i], r);
                if (recorder != null) {
                    rate = r;
                    opened = CHOICES[i];
                    break;
                }
            }
        }
        if (recorder == null) {
            listener.onMicState("no microphone (" + choice + ", " + (rates.length == 1 ? rates[0] + " Hz" : "any rate") + ")");
            return;
        }
        AcousticEchoCanceler echo = null;
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echo = AcousticEchoCanceler.create(recorder.getAudioSessionId());
                if (echo != null) echo.setEnabled(true);
            }
        } catch (RuntimeException unsupported) {
            echo = null;
        }
        try {
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                listener.onMicState("the microphone is in use elsewhere");
                return;
            }
            listener.onMicState("live, " + opened + " " + rate + " Hz, echo canceller " + (echo != null && echo.getEnabled() ? "on" : "none"));
            int target = talk.rate();
            // Down to the channel's rate, or up from a slower microphone (8 kHz forced in the settings, 16 kHz talk).
            int up = rate < target && target % rate == 0 ? target / rate : 1;
            Downsampler downsampler = new Downsampler(rate * up, target);
            short[] chunk = new short[rate / 100];  // 10 ms
            short[] upChunk = new short[chunk.length * up];
            short[] out = new short[upChunk.length];
            int last = 0;
            while (running) {
                int read = recorder.read(chunk, 0, chunk.length);
                if (read < 0) {
                    listener.onMicState("microphone read error " + read);
                    return;
                }
                int n;
                if (up == 1) {
                    n = downsampler.process(chunk, read, out, 0);
                } else {
                    for (int i = 0; i < read; i++) {  // linear interpolation; 8 kHz audio has nothing above 4 kHz to lose
                        for (int k = 0; k < up; k++) upChunk[i * up + k] = (short) (last + (chunk[i] - last) * (k + 1) / up);
                        last = chunk[i];
                    }
                    n = downsampler.process(upChunk, read * up, out, 0);
                }
                if (n > 0) talk.write(out, n);
            }
        } catch (Throwable error) {
            if (running) listener.onMicState("microphone: " + error);
        } finally {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
                // Not started.
            }
            if (echo != null) echo.release();
            recorder.release();
            talk.clear();
        }
    }

    private static int[] rates(String rateChoice) {
        for (int i = 0; i < RATE_CHOICES.length; i++) {
            if (RATE_CHOICES[i].equals(rateChoice)) return new int[] {RATES[i]};
        }
        return RATES;
    }

    private static AudioRecord open(int source, int rate) {
        try {
            int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) return null;
            AudioRecord recorder = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, rate / 10 * 2));
            if (recorder.getState() == AudioRecord.STATE_INITIALIZED) return recorder;
            recorder.release();
        } catch (RuntimeException unsupported) {
            // Try the next rate or source.
        }
        return null;
    }
}
