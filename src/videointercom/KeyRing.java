// SPDX-License-Identifier: Apache-2.0
package videointercom;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The intercom key as HMAC key material. The passphrase is never used directly: it is stretched with
 * PBKDF2-HMAC-SHA256 over a random salt, so a captured handshake or discovery reply costs a full derivation per
 * guessed passphrase instead of one quick hash. Each kiosk makes a new salt every time the plugin starts and tells it
 * to the other kiosks; they derive the key for that salt once and keep it here.
 *
 * Deriving takes a noticeable fraction of a second on a slow tablet, so nothing here may run on Kiosk Satellite's
 * callback thread or Android's main thread. Salts that arrive unasked, from discovery, are limited to a few new
 * derivations a minute, so a host flooding made-up salts cannot keep the CPU busy. Keys that the other kiosk proved
 * are kept apart from keys that were only derived, so such a flood cannot push real kiosks' keys out either. A derived
 * key stays kept even when its proof failed: a kiosk with another passphrase is then not derived for again on every
 * question, and an answer forged with a real kiosk's salt cannot get that kiosk's salt shut out.
 */
final class KeyRing {
    static final int SALT = 16;
    /** About 25 ms on a desktop JDK and a few hundred on a slow ARM tablet: once per kiosk and session. */
    static final int ITERATIONS = 100_000;
    private static final int KEY_BITS = 256;
    private static final int MAX_PROVED = 32, MAX_DERIVED = 64;
    /** Unasked salts: at most BURST new derivations at once, then one per REFILL_MILLIS. */
    private static final int BURST = 3;
    private static final long REFILL_MILLIS = 20_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** This kiosk's own salt for this session, sent to the other kiosks. */
    final byte[] salt = new byte[SALT];
    private final String passphrase;
    private final Object ownLock = new Object();
    private byte[] own;
    /** Keys by salt in hex, least recently used first: the ones another kiosk proved, and the ones only derived. */
    private final Map<String, byte[]> proved = lru(MAX_PROVED), derived = lru(MAX_DERIVED);
    private double tokens = BURST;
    private long refilled = System.currentTimeMillis();
    private int derivations;

    KeyRing(String passphrase) {
        this.passphrase = passphrase.trim();
        RANDOM.nextBytes(salt);
    }

    /** This kiosk's own key; the first call derives it and blocks until it is ready. */
    byte[] own() {
        synchronized (ownLock) {
            if (own == null) own = derive(salt);
            return own;
        }
    }

    /**
     * The key for another kiosk's salt: a kept one, or a fresh derivation. With limited set, as for salts that came
     * unasked, it returns null when too many new salts arrived lately. Call proved() once the other kiosk proved it.
     */
    byte[] keyFor(byte[] peerSalt, boolean limited) {
        if (peerSalt.length != SALT) return null;
        if (Arrays.equals(peerSalt, salt)) return own();
        String id = hex(peerSalt);
        synchronized (this) {
            byte[] kept = proved.get(id);
            if (kept == null) kept = derived.get(id);
            if (kept != null) return kept;
            if (limited) {
                long now = System.currentTimeMillis();
                tokens = Math.min(BURST, tokens + (now - refilled) / (double) REFILL_MILLIS);
                refilled = now;
                if (tokens < 1) return null;
                tokens -= 1;
            }
        }
        byte[] key = derive(peerSalt);
        synchronized (this) {
            derived.put(id, key);
        }
        return key;
    }

    /** Keeps a key the other kiosk proved it holds, apart from the ones only derived. */
    synchronized void proved(byte[] peerSalt, byte[] key) {
        if (peerSalt.length != SALT || Arrays.equals(peerSalt, salt)) return;
        String id = hex(peerSalt);
        derived.remove(id);
        proved.put(id, key);
    }

    /** How many keys this ring derived, for the tests. */
    synchronized int derivations() {
        return derivations;
    }

    private static Map<String, byte[]> lru(int limit) {
        return new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > limit;
            }
        };
    }

    private byte[] derive(byte[] withSalt) {
        synchronized (this) {
            derivations++;
        }
        return derive(passphrase, withSalt);
    }

    static byte[] derive(String passphrase, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(passphrase.trim().toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            text.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return text.toString();
    }

    /** The bytes of a lower- or upper-case hex string of the given length, or null if it is not one. */
    static byte[] unhex(String text, int length) {
        if (text == null || text.length() != length * 2) return null;
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            int high = Character.digit(text.charAt(2 * i), 16), low = Character.digit(text.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) return null;
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }
}
