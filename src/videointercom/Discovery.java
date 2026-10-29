// SPDX-License-Identifier: Apache-2.0
package videointercom;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the other kiosks running this plugin with the same intercom key, so nobody has to type addresses. A kiosk asks
 * who is there with a UDP broadcast on the call port that carries only a fresh random nonce. Every kiosk that hears it
 * answers the asking kiosk alone with its salt, its name and an HMAC over that nonce and name, made with its own key
 * (see KeyRing). The asking kiosk takes an answer only to a nonce it sent in the last few seconds, and only when the
 * HMAC matches the key it derives for that salt, so kiosks with another key stay out of the list and a recorded answer
 * is worthless for any later question. Calling still goes through CallLink's handshake.
 *
 * A kiosk asks once when it starts and whenever probe() is called. A kiosk that hears a question from one it does not
 * know yet asks that one back directly, so both learn of each other at once. Answers and questions back are limited
 * to a few a second, so this kiosk cannot be used to flood the network, and the list keeps at most MAX_KIOSKS kiosks,
 * forgetting the one heard from longest ago and any kiosk silent for five minutes.
 */
final class Discovery {
    static final class Kiosk {
        final String name, host;
        final long seen;

        Kiosk(String name, String host, long seen) {
            this.name = name;
            this.host = host;
            this.seen = seen;
        }
    }

    interface Names {
        String myName();
    }

    private static final String PREFIX = "VI2|";
    private static final int NONCE = 16, MAC = 32;
    static final int MAX_KIOSKS = 32;
    private static final long FORGET_MILLIS = 5 * 60_000;
    /** How long an answer to a question is taken. */
    static final long ANSWER_MILLIS = 15_000;
    private static final int MAX_QUESTIONS = 16;
    /** At most this many answers and questions back in each SEND_WINDOW_MILLIS. */
    private static final int MAX_SENDS = 20;
    private static final long SEND_WINDOW_MILLIS = 10_000;
    /** A kiosk not known yet is asked back at most once in this time. */
    private static final long ASK_BACK_MILLIS = 60_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int port;
    private final KeyRing keys;
    private final Names names;
    /** Where questions go in the tests instead of every broadcast address; null on a kiosk. */
    private final InetSocketAddress target;
    private final Map<String, Kiosk> kiosks = new LinkedHashMap<>();
    /** The nonces of recent questions, by hex, with the address asked or null for a broadcast. */
    private final Map<String, Question> questions = new LinkedHashMap<>();
    private final Map<String, Long> askedBack = new LinkedHashMap<String, Long>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 64;
        }
    };
    private long windowStart;
    private int sends;
    private volatile DatagramSocket socket;
    private volatile boolean running = true;
    private final Thread thread;

    private static final class Question {
        final InetAddress to;
        final long sent;

        Question(InetAddress to, long sent) {
            this.to = to;
            this.sent = sent;
        }
    }

    Discovery(int port, KeyRing keys, Names names) {
        this(port, keys, names, null);
    }

    /** With a target, questions go only there and answers from this machine count, so two can talk over loopback. */
    Discovery(int port, KeyRing keys, Names names, InetSocketAddress target) {
        this.port = port;
        this.keys = keys;
        this.names = names;
        this.target = target;
        thread = new Thread(this::run, "vi-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    /** Asks every kiosk on the network to answer. */
    void probe() {
        ask(null, port);
    }

    /** The kiosks heard from recently, by name. */
    synchronized List<Kiosk> kiosks() {
        long now = System.currentTimeMillis();
        List<Kiosk> list = new ArrayList<>();
        for (Kiosk kiosk : kiosks.values()) if (now - kiosk.seen < FORGET_MILLIS) list.add(kiosk);
        Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return list;
    }

    void stop() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) s.close();
        thread.interrupt();
    }

    private void run() {
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(port));
            socket = s;
            probe();  // ask at start, so this kiosk's list fills and the others learn of it by being asked back
            keys.own();  // derived here, off Kiosk Satellite's threads, before the first answer needs it
            byte[] buffer = new byte[512];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                s.receive(packet);
                try {
                    handle(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8),
                        packet.getAddress(), packet.getPort());
                } catch (RuntimeException ignored) {
                    // A malformed packet; wait for the next one.
                }
            }
        } catch (Throwable stopped) {
            // Closed, or the port is taken: calls still work, only the automatic list stays empty.
        }
    }

    /** One message from the network, on the discovery thread. */
    void handle(String text, InetAddress from, int fromPort) {
        if (!text.startsWith(PREFIX)) return;
        if (target == null && isOwn(from)) return;
        String[] parts = text.substring(PREFIX.length()).split("\\|", 5);
        if (parts.length == 2 && parts[0].equals("Q")) {
            byte[] nonce = KeyRing.unhex(parts[1], NONCE);
            if (nonce != null) question(nonce, from, fromPort);
        } else if (parts.length == 5 && parts[0].equals("A")) {
            answer(parts, from);
        }
    }

    private void question(byte[] nonce, InetAddress from, int fromPort) {
        String host = from.getHostAddress();
        boolean askBack;
        synchronized (this) {
            if (!mayGoOut()) return;
            long now = System.currentTimeMillis();
            Long asked = askedBack.get(host);
            askBack = !kiosks.containsKey(host) && (asked == null || now - asked >= ASK_BACK_MILLIS) && mayGoOut();
            if (askBack) askedBack.put(host, now);
        }
        send(answerText(keys.own(), keys.salt, nonce, names.myName()), from, fromPort);
        if (askBack) ask(from, fromPort);
    }

    private void answer(String[] parts, InetAddress from) {
        String nonce = parts[1].toLowerCase(Locale.ROOT);
        byte[] salt = KeyRing.unhex(parts[2], KeyRing.SALT), proof = KeyRing.unhex(parts[3], MAC);
        if (salt == null || proof == null || KeyRing.unhex(nonce, NONCE) == null) return;
        long now = System.currentTimeMillis();
        synchronized (this) {
            Question asked = questions.get(nonce);
            if (asked == null || now - asked.sent > ANSWER_MILLIS) return;
            if (asked.to != null && !asked.to.equals(from)) return;
        }
        byte[] key = keys.keyFor(salt, true);
        if (key == null) return;
        boolean ok = MessageDigest.isEqual(proof, mac(key, KeyRing.unhex(nonce, NONCE), parts[4]));
        if (!ok) return;
        keys.proved(salt, key);
        String host = from.getHostAddress();
        String name = CallLink.cleanName(parts[4]);
        if (name.isEmpty()) name = host;
        synchronized (this) {
            kiosks.remove(host);
            while (kiosks.size() >= MAX_KIOSKS) {
                Iterator<Map.Entry<String, Kiosk>> oldest = kiosks.entrySet().iterator();
                oldest.next();
                oldest.remove();
            }
            kiosks.put(host, new Kiosk(name, host, System.currentTimeMillis()));
        }
    }

    /** Sends a new question to one kiosk, or to every kiosk when to is null, and remembers its nonce. */
    private void ask(InetAddress to, int toPort) {
        byte[] nonce = new byte[NONCE];
        RANDOM.nextBytes(nonce);
        String hex = KeyRing.hex(nonce);
        long now = System.currentTimeMillis();
        synchronized (this) {
            for (Iterator<Question> it = questions.values().iterator(); it.hasNext(); ) {
                if (now - it.next().sent > ANSWER_MILLIS) it.remove();
            }
            while (questions.size() >= MAX_QUESTIONS) {
                Iterator<Question> oldest = questions.values().iterator();
                oldest.next();
                oldest.remove();
            }
            questions.put(hex, new Question(to, now));
        }
        String text = PREFIX + "Q|" + hex;
        if (to != null) {
            send(text, to, toPort);
        } else if (target != null) {
            send(text, target.getAddress(), target.getPort());
        } else {
            broadcast(text);
        }
    }

    /** Counts one packet going out unasked-for; false once this window's share is used up. Guarded by this. */
    private boolean mayGoOut() {
        long now = System.currentTimeMillis();
        if (now - windowStart >= SEND_WINDOW_MILLIS) {
            windowStart = now;
            sends = 0;
        }
        if (sends >= MAX_SENDS) return false;
        sends++;
        return true;
    }

    /** The answer to a question with the given nonce: "VI2|A|nonce|salt|hmac|name", the binary parts in hex. */
    static String answerText(byte[] key, byte[] salt, byte[] nonce, String myName) {
        String name = CallLink.cleanName(myName);
        return PREFIX + "A|" + KeyRing.hex(nonce) + "|" + KeyRing.hex(salt) + "|" + KeyRing.hex(mac(key, nonce, name)) + "|" + name;
    }

    private static byte[] mac(byte[] key, byte[] nonce, String name) {
        return CallLink.mac(key, "discover", nonce, name.getBytes(StandardCharsets.UTF_8));
    }

    private void send(String text, InetAddress to, int toPort) {
        DatagramSocket s = socket;
        if (s == null) return;
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try {
            s.send(new DatagramPacket(data, data.length, to, toPort));
        } catch (Throwable ignored) {
            // No network right now.
        }
    }

    /** Sends to every broadcast address of this kiosk's networks. */
    private void broadcast(String text) {
        DatagramSocket s = socket;
        if (s == null) return;
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try {
            Set<InetAddress> targets = new HashSet<>();
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null) targets.add(address.getBroadcast());
                }
            }
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (InetAddress to : targets) {
                try {
                    s.send(new DatagramPacket(data, data.length, to, port));
                } catch (Exception unreachable) {
                    // Try the others.
                }
            }
        } catch (Throwable ignored) {
            // No network right now.
        }
    }

    private static boolean isOwn(InetAddress address) {
        try {
            return address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null;
        } catch (Exception unknown) {
            return false;
        }
    }
}
