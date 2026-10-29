// SPDX-License-Identifier: Apache-2.0
package videointercom;

/**
 * Integer-ratio downsampling with a windowed-sinc low-pass first (cut-off just under the new Nyquist), so speech
 * recorded at 48 or 16 kHz does not alias when it becomes 8 kHz telephone audio. Keeps its history between calls.
 */
public final class Downsampler {
    private final int factor;
    private final double[] taps;
    private final double[] history;
    private int phase;
    private int position;

    public Downsampler(int inputRate, int outputRate) {
        if (inputRate % outputRate != 0) throw new IllegalArgumentException(inputRate + " Hz is not a multiple of " + outputRate + " Hz");
        factor = inputRate / outputRate;
        int length = factor == 1 ? 1 : 8 * factor + 1;
        taps = new double[length];
        double cutoff = 0.9 * 0.5 / factor;  // as a fraction of the input rate
        double sum = 0;
        for (int i = 0; i < length; i++) {
            int n = i - length / 2;
            double sinc = n == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * n) / (Math.PI * n);
            double window = length == 1 ? 1 : 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (length - 1));
            taps[i] = sinc * window;
            sum += taps[i];
        }
        for (int i = 0; i < length; i++) taps[i] /= sum;
        history = new double[length];
    }

    /** Filters count input samples and appends every factor-th output to out; returns how many were written. */
    public int process(short[] input, int count, short[] out, int outOffset) {
        int written = 0;
        for (int i = 0; i < count; i++) {
            history[position] = input[i];
            position = (position + 1) % history.length;
            if (++phase < factor) continue;
            phase = 0;
            double acc = 0;
            for (int t = 0; t < taps.length; t++) acc += taps[t] * history[(position + t) % history.length];
            out[outOffset + written++] = (short) Math.max(-32768, Math.min(32767, Math.round(acc)));
        }
        return written;
    }
}
