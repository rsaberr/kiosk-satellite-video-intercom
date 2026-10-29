// SPDX-License-Identifier: Apache-2.0
package videointercom.media;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The parts of an RTSP session description we use: one entry per m= section. */
public final class Sdp {
    public static final class Track {
        public final String media;  // video or audio
        public final List<Integer> payloadTypes = new ArrayList<>();
        public String control = "";
        public String direction = "sendrecv";  // recvonly: the camera sends; sendonly: the ONVIF backchannel
        public String codec = "";  // H264, MPEG4-GENERIC, PCMU...
        public int clock;
        public final Map<String, String> fmtp = new HashMap<>();

        Track(String media) {
            this.media = media;
        }

        /** H.264 SPS and PPS from sprop-parameter-sets, each without a start code. */
        public List<byte[]> parameterSets() {
            List<byte[]> sets = new ArrayList<>();
            String sprop = fmtp.get("sprop-parameter-sets");
            if (sprop == null) return sets;
            for (String part : sprop.split(",")) {
                if (part.isEmpty()) continue;
                try {
                    sets.add(Base64.getDecoder().decode(part.trim()));
                } catch (IllegalArgumentException ignored) {
                    // A broken entry: the stream repeats SPS and PPS before keyframes anyway.
                }
            }
            return sets;
        }

        /** AAC AudioSpecificConfig from config=, for MPEG4-GENERIC. */
        public byte[] audioConfig() {
            String hex = fmtp.get("config");
            if (hex == null || hex.length() % 2 != 0) return new byte[0];
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            return out;
        }

        public int fmtpInt(String key, int fallback) {
            try {
                return Integer.parseInt(fmtp.get(key));
            } catch (RuntimeException e) {
                return fallback;
            }
        }
    }

    public final List<Track> tracks = new ArrayList<>();

    public static Sdp parse(String text) {
        Sdp sdp = new Sdp();
        Track track = null;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("m=")) {
                String[] parts = line.substring(2).split(" ");
                track = new Track(parts[0]);
                for (int i = 3; i < parts.length; i++) {
                    try {
                        track.payloadTypes.add(Integer.parseInt(parts[i]));
                    } catch (NumberFormatException ignored) {
                        // Not a payload number.
                    }
                }
                sdp.tracks.add(track);
            } else if (track != null && line.startsWith("a=")) {
                String attribute = line.substring(2);
                if (attribute.startsWith("control:")) track.control = attribute.substring(8).trim();
                else if (attribute.equals("recvonly") || attribute.equals("sendonly") || attribute.equals("sendrecv")) track.direction = attribute;
                else if (attribute.startsWith("rtpmap:")) {
                    String[] parts = attribute.substring(7).split("[ /]");
                    if (parts.length >= 3) {
                        track.codec = parts[1].toUpperCase(Locale.ROOT);
                        try {
                            track.clock = Integer.parseInt(parts[2]);
                        } catch (NumberFormatException ignored) {
                            // Keep 0.
                        }
                    }
                } else if (attribute.startsWith("fmtp:")) {
                    int space = attribute.indexOf(' ');
                    if (space < 0) continue;
                    for (String pair : attribute.substring(space + 1).split(";")) {
                        int eq = pair.indexOf('=');
                        if (eq > 0) track.fmtp.put(pair.substring(0, eq).trim().toLowerCase(Locale.ROOT), pair.substring(eq + 1).trim());
                    }
                }
            }
        }
        return sdp;
    }

    /** The first track of this media type the camera sends (recvonly or sendrecv), or null. */
    public Track receive(String media) {
        for (Track track : tracks) if (track.media.equals(media) && !"sendonly".equals(track.direction)) return track;
        return null;
    }

    /** The ONVIF backchannel: an audio track the client sends on, or null. */
    public Track backchannel() {
        for (Track track : tracks) if (track.media.equals("audio") && "sendonly".equals(track.direction)) return track;
        return null;
    }
}
