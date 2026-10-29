// SPDX-License-Identifier: Apache-2.0
package videointercom;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * One call between two kiosks over a single TCP connection: a short handshake that proves both sides hold the same
 * intercom key, then signalling (accept, refuse, bye) and the voice, both ways. Frames are a type byte, a 16-bit
 * length and the payload. The key never crosses the network: each side answers the other's random challenge with an
 * HMAC-SHA256, so a kiosk without the key can neither ring this one nor pose as the kiosk being called.
 *
 * The HMAC key is the passphrase stretched with PBKDF2 over the called kiosk's salt (see KeyRing). The called kiosk
 * opens with a challenge of a random nonce and its salt; the caller derives the key for that salt, or takes the one it
 * kept from an earlier call, and answers with its own nonce, its proof and its name. The called kiosk checks the proof
 * with its own key and, when it rings, proves the key back over both nonces. Kiosks still running the first version of
 * this protocol, which used a plain hash of the passphrase, cannot call or be called. The voice itself is not
 * encrypted; it stays on the local network like Kiosk Satellite's own intercom.
 */
final class CallLink {
    interface Listener {
        /** A frame from the other kiosk, on the link's reader thread. */
        void onFrame(CallLink link, int type, byte[] payload);
        /** The connection ended; reason is null after close(). */
        void onClosed(CallLink link, String reason);
    }

    /** The other kiosk said no: busy, dnd, declined, no answer, or key; or it runs an older version. */
    static final class Refused extends IOException {
        final String reason;

        Refused(String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    static final int CHALLENGE = 1, HELLO = 2, RINGING = 3, REFUSED = 4, ACCEPT = 5, BYE = 6, AUDIO = 7, PING = 8;
    static final int MAX_PAYLOAD = 16 * 1024;
    private static final int NONCE = 16, MAC = 32;
    static final int MAX_NAME = 80;
    private static final String LABEL = "ks-video-intercom-2:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    /** The other kiosk's name, as it introduced itself. */
    final String peerName;
    /** The other kiosk's address, for its camera stream. */
    final String peerHost;
    private volatile boolean closed;
    private Thread reader;

    private CallLink(Socket socket, DataInputStream in, OutputStream out, String peerName) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.peerName = peerName;
        this.peerHost = socket.getInetAddress().getHostAddress();
    }

    /**
     * Calls another kiosk: connects, proves the key and asks to ring. Returns once the other kiosk rings, after it
     * proved the key too, or throws Refused with its reason. The first call to a kiosk derives the key for its salt,
     * which takes a moment on a slow tablet; later calls reuse it. Either way this must run on a network thread.
     */
    static CallLink dial(String host, int port, KeyRing keys, String myName) throws IOException {
        Socket socket = new Socket();
        try {
            if (keys == null) throw new IOException("no intercom key");
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), 4000);
            socket.setSoTimeout(8000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 16 * 1024));
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 4 * 1024);
            byte[][] frame = new byte[1][];
            int type = readFrame(in, frame);
            if (type == CHALLENGE && frame[0].length == NONCE) throw new Refused("version");
            if (type != CHALLENGE || frame[0].length != NONCE + KeyRing.SALT) throw new IOException("not a video intercom");
            byte[] theirs = new byte[NONCE], salt = new byte[KeyRing.SALT];
            System.arraycopy(frame[0], 0, theirs, 0, NONCE);
            System.arraycopy(frame[0], NONCE, salt, 0, KeyRing.SALT);
            byte[] key = keys.keyFor(salt, false);
            byte[] mine = new byte[NONCE];
            RANDOM.nextBytes(mine);
            byte[] name = name(myName);
            byte[] hello = new byte[NONCE + MAC + name.length];
            System.arraycopy(mine, 0, hello, 0, NONCE);
            System.arraycopy(mac(key, "caller", theirs, mine), 0, hello, NONCE, MAC);
            System.arraycopy(name, 0, hello, NONCE + MAC, name.length);
            writeFrame(out, HELLO, hello, hello.length);
            type = readFrame(in, frame);
            if (type == REFUSED) throw new Refused(new String(frame[0], StandardCharsets.UTF_8));
            if (type != RINGING || frame[0].length < MAC) throw new IOException("unexpected answer " + type);
            byte[] proof = new byte[MAC];
            System.arraycopy(frame[0], 0, proof, 0, MAC);
            if (!MessageDigest.isEqual(proof, mac(key, "callee", mine, theirs))) throw new IOException("the other kiosk has a different key");
            keys.proved(salt, key);
            String peer = cleanName(new String(frame[0], MAC, frame[0].length - MAC, StandardCharsets.UTF_8));
            return new CallLink(socket, in, out, peer.isEmpty() ? host : peer);
        } catch (IOException | RuntimeException error) {
            try { socket.close(); } catch (IOException ignored) {}
            throw error;
        }
    }

    /**
     * The answering side of dial(): challenges the caller and checks its proof. Returns the link, not yet ringing:
     * the caller waits for ring() or refuse(). A caller with another key gets "key" and an exception. Checking the
     * proof needs this kiosk's own key, which may still be being derived just after a start; it then waits for it, so
     * this must run on a network thread.
     */
    static CallLink answer(Socket socket, KeyRing keys) throws IOException {
        try {
            if (keys == null) throw new IOException("no intercom key");
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(8000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 16 * 1024));
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 4 * 1024);
            byte[] mine = new byte[NONCE];
            RANDOM.nextBytes(mine);
            byte[] challenge = new byte[NONCE + KeyRing.SALT];
            System.arraycopy(mine, 0, challenge, 0, NONCE);
            System.arraycopy(keys.salt, 0, challenge, NONCE, KeyRing.SALT);
            writeFrame(out, CHALLENGE, challenge, challenge.length);
            byte[][] frame = new byte[1][];
            int type = readFrame(in, frame);
            if (type != HELLO || frame[0].length < NONCE + MAC) throw new IOException("not a video intercom call");
            byte[] theirs = new byte[NONCE], proof = new byte[MAC];
            System.arraycopy(frame[0], 0, theirs, 0, NONCE);
            System.arraycopy(frame[0], NONCE, proof, 0, MAC);
            byte[] key = keys.own();
            if (!MessageDigest.isEqual(proof, mac(key, "caller", mine, theirs))) {
                byte[] reason = "key".getBytes(StandardCharsets.UTF_8);
                writeFrame(out, REFUSED, reason, reason.length);
                throw new IOException("a caller with a different key");
            }
            String peer = cleanName(new String(frame[0], NONCE + MAC, frame[0].length - NONCE - MAC, StandardCharsets.UTF_8));
            CallLink link = new CallLink(socket, in, out, peer.isEmpty() ? socket.getInetAddress().getHostAddress() : peer);
            link.challenge = mine;
            link.callerNonce = theirs;
            link.key = key;
            return link;
        } catch (IOException | RuntimeException error) {
            try { socket.close(); } catch (IOException ignored) {}
            throw error;
        }
    }

    private byte[] challenge, callerNonce, key;

    /** Tells the caller this kiosk rings, with the proof that it holds the key too. */
    void ring(String myName) throws IOException {
        byte[] name = name(myName);
        byte[] payload = new byte[MAC + name.length];
        System.arraycopy(mac(key, "callee", callerNonce, challenge), 0, payload, 0, MAC);
        System.arraycopy(name, 0, payload, MAC, name.length);
        send(RINGING, payload, payload.length);
    }

    /** Says no with a reason, then closes. */
    void refuse(String reason) {
        try {
            byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
            send(REFUSED, bytes, bytes.length);
        } catch (IOException | RuntimeException ignored) {
            // Closing anyway.
        }
        close();
    }

    /** Starts the reader thread; frames and the end of the link go to the listener. */
    synchronized void start(Listener listener) {
        Thread thread = new Thread(() -> read(listener), "vi-link");
        thread.setDaemon(true);
        reader = thread;
        thread.start();
    }

    void send(int type, byte[] payload, int length) throws IOException {
        if (closed) throw new IOException("closed");
        writeFrame(out, type, payload, length);
    }

    /** Sends a frame and ignores a failure: the reader notices a dead link on its own. */
    void sendQuietly(int type, byte[] payload) {
        try {
            send(type, payload, payload.length);
        } catch (IOException | RuntimeException ignored) {
            // Reported by the reader.
        }
    }

    void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    boolean isClosed() {
        return closed;
    }

    private void read(Listener listener) {
        String reason = null;
        try {
            // The other side pings every few seconds, so a long silence means the link is gone.
            socket.setSoTimeout(12_000);
            byte[][] frame = new byte[1][];
            while (!closed) {
                int type = readFrame(in, frame);
                listener.onFrame(this, type, frame[0]);
            }
        } catch (SocketTimeoutException quiet) {
            if (!closed) reason = "the other kiosk stopped answering";
        } catch (EOFException ended) {
            if (!closed) reason = "the other kiosk hung up";
        } catch (Throwable error) {
            if (!closed) reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        } finally {
            close();
            try {
                listener.onClosed(this, reason);
            } catch (Throwable ignored) {
                // Must not take the app down with it.
            }
        }
    }

    static void writeFrame(OutputStream out, int type, byte[] payload, int length) throws IOException {
        if (length > MAX_PAYLOAD) throw new IOException("frame too large");
        synchronized (out) {
            out.write(type);
            out.write(length >> 8);
            out.write(length);
            out.write(payload, 0, length);
            out.flush();
        }
    }

    /** Reads one frame into holder[0] and returns its type. */
    static int readFrame(DataInputStream in, byte[][] holder) throws IOException {
        int type = in.read();
        if (type < 0) throw new EOFException();
        int length = in.readUnsignedShort();
        if (length > MAX_PAYLOAD) throw new IOException("frame too large");
        byte[] payload = new byte[length];
        in.readFully(payload);
        holder[0] = payload;
        return type;
    }

    static byte[] mac(byte[] key, String role, byte[] first, byte[] second) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            hmac.update((LABEL + role).getBytes(StandardCharsets.UTF_8));
            hmac.update(first);
            hmac.update(second);
            return hmac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] name(String name) {
        return cleanName(name).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A kiosk name fit to show: trimmed, control characters and line breaks dropped, and cut to MAX_NAME characters
     * without splitting a character in two. Used for this kiosk's own name and for every name another kiosk sends.
     */
    static String cleanName(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        StringBuilder clean = new StringBuilder(Math.min(trimmed.length(), MAX_NAME + 1));
        for (int i = 0; i < trimmed.length() && clean.length() <= MAX_NAME; i++) {
            char c = trimmed.charAt(i);
            if (!Character.isISOControl(c) && c != '\u2028' && c != '\u2029') clean.append(c);
        }
        int end = clean.length();
        if (end > MAX_NAME) end = Character.isHighSurrogate(clean.charAt(MAX_NAME - 1)) ? MAX_NAME - 1 : MAX_NAME;
        return clean.substring(0, end).trim();
    }
}
