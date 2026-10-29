// SPDX-License-Identifier: Apache-2.0
package videointercom.media;

/** The picture size from an H.264 sequence parameter set, enough to configure a decoder before its first frame. */
public final class SpsInfo {
    public final int width;
    public final int height;

    private SpsInfo(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** sps is the NAL unit without a start code. Returns null when it cannot be read. */
    public static SpsInfo parse(byte[] sps) {
        try {
            Bits bits = new Bits(unescape(sps));
            bits.skip(8);  // NAL header
            int profile = bits.read(8);
            bits.skip(16);  // constraint flags, level
            bits.ue();  // seq_parameter_set_id
            int chroma = 1;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83 || profile == 86 || profile == 118 || profile == 128) {
                chroma = bits.ue();
                if (chroma == 3) bits.skip(1);
                bits.ue();
                bits.ue();
                bits.skip(1);
                if (bits.read(1) == 1) {
                    for (int i = 0; i < (chroma != 3 ? 8 : 12); i++) {
                        if (bits.read(1) == 1) skipScalingList(bits, i < 6 ? 16 : 64);
                    }
                }
            }
            bits.ue();  // log2_max_frame_num
            int pocType = bits.ue();
            if (pocType == 0) bits.ue();
            else if (pocType == 1) {
                bits.skip(1);
                bits.se();
                bits.se();
                int cycle = bits.ue();
                for (int i = 0; i < cycle; i++) bits.se();
            }
            bits.ue();  // max_num_ref_frames
            bits.skip(1);
            int widthMbs = bits.ue() + 1;
            int heightMapUnits = bits.ue() + 1;
            int frameMbsOnly = bits.read(1);
            if (frameMbsOnly == 0) bits.skip(1);
            bits.skip(1);
            int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            if (bits.read(1) == 1) {
                cropLeft = bits.ue();
                cropRight = bits.ue();
                cropTop = bits.ue();
                cropBottom = bits.ue();
            }
            int cropUnitX = chroma == 0 || chroma == 3 ? 1 : 2;
            int cropUnitY = (chroma == 1 ? 2 : 1) * (2 - frameMbsOnly);
            int width = widthMbs * 16 - cropUnitX * (cropLeft + cropRight);
            int height = (2 - frameMbsOnly) * heightMapUnits * 16 - cropUnitY * (cropTop + cropBottom);
            return width > 0 && height > 0 ? new SpsInfo(width, height) : null;
        } catch (RuntimeException broken) {
            return null;
        }
    }

    private static void skipScalingList(Bits bits, int size) {
        int last = 8, next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) next = (last + bits.se() + 256) % 256;
            last = next == 0 ? last : next;
        }
    }

    /** Removes emulation-prevention bytes (00 00 03). */
    private static byte[] unescape(byte[] data) {
        byte[] out = new byte[data.length];
        int n = 0, zeros = 0;
        for (byte b : data) {
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue;
            }
            out[n++] = b;
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static final class Bits {
        private final byte[] data;
        private int position;

        Bits(byte[] data) {
            this.data = data;
        }

        int read(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                int bit = (data[position / 8] >> (7 - position % 8)) & 1;
                position++;
                value = (value << 1) | bit;
            }
            return value;
        }

        void skip(int count) {
            position += count;
        }

        int ue() {
            int zeros = 0;
            while (read(1) == 0) zeros++;
            return (1 << zeros) - 1 + read(zeros);
        }

        int se() {
            int value = ue();
            return (value & 1) == 1 ? (value + 1) / 2 : -(value / 2);
        }
    }
}
