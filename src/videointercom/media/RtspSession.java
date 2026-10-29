// SPDX-License-Identifier: Apache-2.0
package videointercom.media;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small RTSP client with RTP interleaved over the same TCP connection: DESCRIBE (digest auth), SETUP, PLAY, a
 * GET_PARAMETER keepalive and a reader thread for the RTP. Servers drop a session after a while without one.
 */
public final class RtspSession {
    public interface Listener {
        /** One interleaved RTP or RTCP packet; the buffer is reused after the call returns. */
        void onPacket(int channel, byte[] buffer, int offset, int length);
        /** The session ended; reason is null after close(). */
        void onClosed(String reason);
    }

    /** The most a device may make us allocate: it runs inside Kiosk Satellite, so a broken one must not take it down. */
    static final int MAX_BODY = 64 * 1024;
    static final int MAX_LINE = 8 * 1024;
    private static final Pattern REALM = Pattern.compile("realm=\"([^\"]*)\"");
    private static final Pattern NONCE = Pattern.compile("nonce=\"([^\"]*)\"");

    private final String host;
    private final int port;
    private final String url;
    private final String username;
    private final String password;
    private final long keepaliveMillis;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int cseq;
    private String session;
    private String realm, nonce;
    private String contentBase;
    private volatile boolean closed;
    private Thread reader;

    public RtspSession(String host, int port, String path, String username, String password) {
        this(host, port, path, username, password, 10_000);
    }

    RtspSession(String host, int port, String path, String username, String password, long keepaliveMillis) {
        this.host = host;
        this.port = port;
        this.url = "rtsp://" + host + ":" + port + (path.startsWith("/") ? path : "/" + path);
        this.username = username;
        this.password = password;
        this.keepaliveMillis = keepaliveMillis;
    }

    public String url() {
        return url;
    }

    /** OPTIONS and DESCRIBE; extra headers (such as the ONVIF backchannel Require) go on every request. */
    public synchronized Sdp describe(Map<String, String> extra) throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(5000);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        out = socket.getOutputStream();
        request("OPTIONS", url, extra);
        Map<String, String> headers = new LinkedHashMap<>(extra);
        headers.put("Accept", "application/sdp");
        Response response = request("DESCRIBE", url, headers);
        contentBase = response.headers.get("content-base");
        return Sdp.parse(response.body);
    }

    /** SETUP one track interleaved on channels base and base + 1; returns the RTP channel the server chose. */
    public synchronized int setup(Sdp.Track track, int base, Map<String, String> extra) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(extra);
        headers.put("Transport", "RTP/AVP/TCP;unicast;interleaved=" + base + "-" + (base + 1));
        Response response = request("SETUP", controlUrl(track.control), headers);
        String sessionHeader = response.headers.get("session");
        if (sessionHeader != null) session = sessionHeader.split(";")[0].trim();
        Matcher channel = Pattern.compile("interleaved=(\\d+)").matcher(response.headers.getOrDefault("transport", ""));
        return channel.find() ? Integer.parseInt(channel.group(1)) : base;
    }

    public synchronized void play(Map<String, String> extra) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(extra);
        headers.put("Range", "npt=0.000-");
        request("PLAY", aggregateUrl(), headers);
    }

    /** Starts the reader thread; from here on the RTSP answers to keepalives are read and dropped by it. */
    public synchronized void start(Listener listener, String threadName) {
        Thread thread = new Thread(() -> read(listener), threadName);
        thread.setDaemon(true);
        reader = thread;
        thread.start();
    }

    /** Sends one RTP packet on an interleaved channel, for the backchannel. Safe from any thread. */
    public void send(int channel, byte[] packet, int length) throws IOException {
        byte[] frame = new byte[4 + length];
        frame[0] = '$';
        frame[1] = (byte) channel;
        frame[2] = (byte) (length >> 8);
        frame[3] = (byte) length;
        System.arraycopy(packet, 0, frame, 4, length);
        OutputStream o = out;
        if (o == null) throw new IOException("not connected");
        synchronized (o) {
            o.write(frame);
        }
    }

    /** TEARDOWN as a courtesy, then closes the connection and waits briefly for the reader. */
    public void close() {
        closed = true;
        try {
            OutputStream o = out;
            if (o != null && session != null) {
                synchronized (o) {
                    o.write(requestBytes("TEARDOWN", aggregateUrl(), new LinkedHashMap<>()));
                }
            }
        } catch (IOException ignored) {
            // The connection may already be gone.
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // Closing is best effort.
        }
        Thread r = reader;
        if (r != null && r != Thread.currentThread()) {
            try {
                r.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void read(Listener listener) {
        String reason = null;
        byte[] buffer = new byte[65536];
        long lastKeepalive = System.currentTimeMillis();
        try {
            socket.setSoTimeout(1000);
            while (!closed) {
                if (System.currentTimeMillis() - lastKeepalive >= keepaliveMillis) {
                    synchronized (out) {
                        out.write(requestBytes("GET_PARAMETER", aggregateUrl(), new LinkedHashMap<>()));
                    }
                    lastKeepalive = System.currentTimeMillis();
                }
                int first;
                try {
                    first = in.read();
                } catch (SocketTimeoutException quiet) {
                    continue;
                }
                if (first < 0) throw new IOException("the other kiosk closed the stream");
                if (first == '$') {
                    int channel = in.readUnsignedByte();
                    int length = in.readUnsignedShort();
                    in.readFully(buffer, 0, length);
                    listener.onPacket(channel, buffer, 0, length);
                } else if (first == 'R') {
                    skipResponse(in);  // an answer to a keepalive
                }
            }
        } catch (Throwable error) {
            if (!closed) reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        } finally {
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
            listener.onClosed(reason);
        }
    }

    private String controlUrl(String control) {
        if (control == null || control.isEmpty() || control.equals("*")) return aggregateUrl();
        if (control.startsWith("rtsp://")) return control;
        String base = contentBase != null ? contentBase : url;
        return base.endsWith("/") ? base + control : base + "/" + control;
    }

    private String aggregateUrl() {
        String base = contentBase != null ? contentBase : url;
        return base.endsWith("/") ? base : base + "/";
    }

    private static final class Response {
        int status;
        final Map<String, String> headers = new LinkedHashMap<>();
        String body = "";
    }

    private Response request(String method, String uri, Map<String, String> extra) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            out.write(requestBytes(method, uri, extra));
            out.flush();
            Response response = readResponse(in);
            if (response.status == 401 && realm == null) {
                String challenge = response.headers.getOrDefault("www-authenticate", "");
                Matcher r = REALM.matcher(challenge), n = NONCE.matcher(challenge);
                if (r.find() && n.find()) {
                    realm = r.group(1);
                    nonce = n.group(1);
                    continue;
                }
            }
            if (response.status == 401) throw new IOException("the other kiosk refused the RTSP username or password");
            if (response.status != 200) throw new IOException(method + " failed with " + response.status);
            return response;
        }
        throw new IOException(method + " failed: authentication refused");
    }

    private byte[] requestBytes(String method, String uri, Map<String, String> extra) {
        StringBuilder request = new StringBuilder();
        request.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n");
        request.append("CSeq: ").append(++cseq).append("\r\n");
        request.append("User-Agent: ks-video-intercom\r\n");
        if (session != null) request.append("Session: ").append(session).append("\r\n");
        if (realm != null) request.append("Authorization: ").append(digest(method, uri)).append("\r\n");
        for (Map.Entry<String, String> header : extra.entrySet()) request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        request.append("\r\n");
        return request.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String digest(String method, String uri) {
        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        return "Digest username=\"" + username + "\", realm=\"" + realm + "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", response=\"" + md5(ha1 + ":" + nonce + ":" + ha2) + "\"";
    }

    private static String md5(String text) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8))) hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads one response, skipping interleaved packets the server may send before it. */
    private static Response readResponse(DataInputStream in) throws IOException {
        int first = in.read();
        while (first == '$') {
            in.readUnsignedByte();
            int length = in.readUnsignedShort();
            in.skipBytes(length);
            first = in.read();
        }
        if (first < 0) throw new IOException("connection closed");
        String status = (char) first + line(in);
        Response response = new Response();
        String[] parts = status.split(" ");
        response.status = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int length = 0;
        for (String line = line(in); !line.isEmpty(); line = line(in)) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
            response.headers.put(key, value);
            if (key.equals("content-length")) length = contentLength(value);
        }
        if (length > 0) {
            byte[] body = new byte[length];
            in.readFully(body);
            response.body = new String(body, StandardCharsets.UTF_8);
        }
        return response;
    }

    private static void skipResponse(DataInputStream in) throws IOException {
        int length = 0;
        line(in);  // rest of the status line
        for (String line = line(in); !line.isEmpty(); line = line(in)) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = contentLength(line.substring(15).trim());
        }
        in.skipBytes(length);
    }

    private static int contentLength(String value) throws IOException {
        int length;
        try {
            length = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IOException("bad Content-Length");
        }
        if (length < 0 || length > MAX_BODY) throw new IOException("RTSP body of " + length + " bytes is too large");
        return length;
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int c = in.read(); c >= 0 && c != '\n'; c = in.read()) {
            if (c == '\r') continue;
            if (line.size() >= MAX_LINE) throw new IOException("RTSP header line too long");
            line.write(c);
        }
        return new String(line.toByteArray(), StandardCharsets.UTF_8);
    }
}
