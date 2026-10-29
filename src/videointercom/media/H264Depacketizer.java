// SPDX-License-Identifier: Apache-2.0
package videointercom.media;

import java.io.ByteArrayOutputStream;

/**
 * RTP H.264 (RFC 6184) to Annex-B access units: single NAL units, STAP-A aggregates and FU-A fragments. An access
 * unit is complete on the RTP marker bit or when the timestamp moves on. A lost fragment drops that NAL, not the stream.
 */
public final class H264Depacketizer {
    public interface Sink {
        /** One complete frame in Annex-B form; keyframe when it holds an IDR slice. */
        void onAccessUnit(byte[] annexB, long rtpTimestamp, boolean keyframe);
    }

    private static final byte[] START = {0, 0, 0, 1};
    /** A 2560x1920 keyframe is about 0.3 MB; anything near this is a broken or hostile stream, so the frame is dropped. */
    static final int MAX_UNIT = 4 * 1024 * 1024;

    private final Sink sink;
    private final ByteArrayOutputStream unit = new ByteArrayOutputStream(256 * 1024);
    private final ByteArrayOutputStream fragment = new ByteArrayOutputStream(256 * 1024);
    private boolean fragmenting;
    private boolean keyframe;
    private boolean picture;  // the unit holds a slice, not only SPS/PPS/SEI
    private long timestamp = -1;
    private int lastSequence = -1;

    public H264Depacketizer(Sink sink) {
        this.sink = sink;
    }

    public void onRtp(byte[] packet, int offset, int length) {
        if (length < 12) return;
        int csrc = packet[offset] & 0x0F;
        boolean extension = (packet[offset] & 0x10) != 0;
        boolean marker = (packet[offset + 1] & 0x80) != 0;
        int sequence = ((packet[offset + 2] & 0xFF) << 8) | (packet[offset + 3] & 0xFF);
        long ts = ((packet[offset + 4] & 0xFFL) << 24) | ((packet[offset + 5] & 0xFFL) << 16) | ((packet[offset + 6] & 0xFFL) << 8) | (packet[offset + 7] & 0xFFL);
        int header = 12 + 4 * csrc;
        if (extension && length >= header + 4) header += 4 + 4 * (((packet[offset + header + 2] & 0xFF) << 8) | (packet[offset + header + 3] & 0xFF));
        int padding = (packet[offset] & 0x20) != 0 ? packet[offset + length - 1] & 0xFF : 0;
        int start = offset + header, end = offset + length - padding;
        if (start >= end) return;

        if (lastSequence >= 0 && sequence != ((lastSequence + 1) & 0xFFFF)) fragmenting = false;  // a gap breaks the fragment
        lastSequence = sequence;
        if (timestamp >= 0 && ts != timestamp && picture) flush();
        timestamp = ts;

        int type = packet[start] & 0x1F;
        if (type >= 1 && type <= 23) {
            nal(packet, start, end - start);
        } else if (type == 24) {  // STAP-A
            int at = start + 1;
            while (at + 2 <= end) {
                int size = ((packet[at] & 0xFF) << 8) | (packet[at + 1] & 0xFF);
                at += 2;
                if (size == 0 || at + size > end) break;
                nal(packet, at, size);
                at += size;
            }
        } else if (type == 28 && end - start >= 2) {  // FU-A
            int indicator = packet[start] & 0xFF, fuHeader = packet[start + 1] & 0xFF;
            boolean first = (fuHeader & 0x80) != 0, last = (fuHeader & 0x40) != 0;
            if (first) {
                fragment.reset();
                fragment.write((indicator & 0xE0) | (fuHeader & 0x1F));
                fragmenting = true;
            }
            if (fragmenting && fragment.size() + (end - start - 2) > MAX_UNIT) {
                fragment.reset();
                fragmenting = false;
                dropUnit();
            }
            if (fragmenting) {
                fragment.write(packet, start + 2, end - start - 2);
                if (last) {
                    byte[] nal = fragment.toByteArray();
                    nal(nal, 0, nal.length);
                    fragmenting = false;
                }
            }
        }
        // Some cameras set the marker after SPS/PPS: keep those with the slice that follows, so keyframes stay decodable.
        if (marker && picture) flush();
    }

    private void nal(byte[] data, int offset, int length) {
        if (unit.size() + START.length + length > MAX_UNIT) {
            dropUnit();  // no marker or slice arrived to end it: a broken stream
            return;
        }
        int type = data[offset] & 0x1F;
        if (type == 5) keyframe = true;
        if (type >= 1 && type <= 5) picture = true;
        unit.write(START, 0, START.length);
        unit.write(data, offset, length);
    }

    private void dropUnit() {
        unit.reset();
        keyframe = false;
        picture = false;
    }

    private void flush() {
        if (unit.size() > 0) sink.onAccessUnit(unit.toByteArray(), timestamp, keyframe);
        unit.reset();
        keyframe = false;
        picture = false;
    }
}
