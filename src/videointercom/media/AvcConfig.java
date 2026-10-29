// SPDX-License-Identifier: Apache-2.0
package videointercom.media;

import java.util.Locale;

/**
 * The H.264 parameter sets at the start of a keyframe (Annex B): the SPS and PPS a decoder can be given up front
 * as csd-0 and csd-1, and the profile, level and size the SPS declares, for the log.
 */
public final class AvcConfig {
    private static final byte[] START = {0, 0, 0, 1};
    private static final int NAL_SPS = 7, NAL_PPS = 8;

    /** Each NAL unit with its header byte, without a start code; null when the keyframe lacks it. */
    public final byte[] sps, pps;
    public final SpsInfo size;

    private AvcConfig(byte[] sps, byte[] pps) {
        this.sps = sps;
        this.pps = pps;
        size = sps == null ? null : SpsInfo.parse(sps);
    }

    public static AvcConfig of(byte[] annexB) {
        return new AvcConfig(nal(annexB, NAL_SPS), nal(annexB, NAL_PPS));
    }

    public boolean complete() {
        return sps != null && pps != null;
    }

    /** csd-0 or csd-1: the NAL unit behind a 4-byte start code, as MediaCodec takes it. */
    public static byte[] withStartCode(byte[] nal) {
        byte[] out = new byte[START.length + nal.length];
        System.arraycopy(START, 0, out, 0, START.length);
        System.arraycopy(nal, 0, out, START.length, nal.length);
        return out;
    }

    public int profile() {
        return sps != null && sps.length > 3 ? sps[1] & 0xFF : -1;
    }

    public int level() {
        return sps != null && sps.length > 3 ? sps[3] & 0xFF : -1;
    }

    /** Such as "High profile, level 5.1, 640x480". */
    public String describe() {
        if (sps == null) return "no SPS";
        String name;
        switch (profile()) {
            case 66: name = "Baseline"; break;
            case 77: name = "Main"; break;
            case 88: name = "Extended"; break;
            case 100: name = "High"; break;
            case 110: name = "High 10"; break;
            case 122: name = "High 4:2:2"; break;
            case 244: name = "High 4:4:4"; break;
            default: name = "profile " + profile();
        }
        String text = String.format(Locale.ROOT, "%s profile, level %.1f", name, level() / 10.0);
        return size == null ? text : text + ", " + size.width + "x" + size.height;
    }

    /** The first NAL unit of a type, found behind a 3- or 4-byte start code. */
    static byte[] nal(byte[] annexB, int type) {
        int i = 0;
        while ((i = next(annexB, i)) >= 0) {
            int start = i;
            int end = next(annexB, start);
            end = end < 0 ? annexB.length : end - 3;  // back over the next start code
            // A NAL unit never ends in a zero byte: what is left is a 4-byte start code's first byte or padding.
            while (end > start && annexB[end - 1] == 0) end--;
            if (start < annexB.length && (annexB[start] & 0x1F) == type && end > start) {
                byte[] out = new byte[end - start];
                System.arraycopy(annexB, start, out, 0, out.length);
                return out;
            }
            i = start;
        }
        return null;
    }

    /** The index just after the next 00 00 01 from i, or -1. */
    private static int next(byte[] data, int i) {
        for (int j = i; j + 2 < data.length; j++) {
            if (data[j] == 0 && data[j + 1] == 0 && data[j + 2] == 1) return j + 3;
        }
        return -1;
    }
}
