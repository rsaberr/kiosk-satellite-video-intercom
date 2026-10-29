// SPDX-License-Identifier: Apache-2.0
package videointercom.media;

/**
 * RTP MPEG4-GENERIC AAC (RFC 3640, mode AAC-hbr) to raw AAC frames: a 16-bit AU-headers length, then one header per
 * frame (sizelength bits of size, indexlength bits of index), then the frames back to back.
 */
public final class AacDepacketizer {
    public interface Sink {
        void onAacFrame(byte[] frame, long rtpTimestamp);
    }

    private final Sink sink;
    private final int sizeLength;
    private final int indexLength;
    private final int samplesPerFrame = 1024;

    public AacDepacketizer(Sink sink, int sizeLength, int indexLength) {
        this.sink = sink;
        this.sizeLength = sizeLength <= 0 ? 13 : sizeLength;
        this.indexLength = indexLength < 0 ? 3 : indexLength;
    }

    public void onRtp(byte[] packet, int offset, int length) {
        if (length < 14) return;
        int csrc = packet[offset] & 0x0F;
        long ts = ((packet[offset + 4] & 0xFFL) << 24) | ((packet[offset + 5] & 0xFFL) << 16) | ((packet[offset + 6] & 0xFFL) << 8) | (packet[offset + 7] & 0xFFL);
        int at = offset + 12 + 4 * csrc;
        int end = offset + length;
        if (at + 2 > end) return;
        int headerBits = ((packet[at] & 0xFF) << 8) | (packet[at + 1] & 0xFF);
        at += 2;
        int headerBytes = (headerBits + 7) / 8;
        int headerStart = at;
        int data = at + headerBytes;
        if (data > end) return;
        int bitsPerHeader = sizeLength + indexLength;
        int count = bitsPerHeader == 0 ? 0 : headerBits / bitsPerHeader;
        for (int i = 0; i < count; i++) {
            int size = bits(packet, headerStart, i * bitsPerHeader, sizeLength);
            if (size <= 0 || data + size > end) return;
            byte[] frame = new byte[size];
            System.arraycopy(packet, data, frame, 0, size);
            sink.onAacFrame(frame, ts + (long) i * samplesPerFrame);
            data += size;
        }
    }

    private static int bits(byte[] buffer, int byteOffset, int bitOffset, int count) {
        int value = 0;
        for (int i = 0; i < count; i++) {
            int bit = bitOffset + i;
            value = (value << 1) | ((buffer[byteOffset + bit / 8] >> (7 - bit % 8)) & 1);
        }
        return value;
    }
}
