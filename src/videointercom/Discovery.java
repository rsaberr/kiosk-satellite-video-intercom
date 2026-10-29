// SPDX-License-Identifier: Apache-2.0
package videointercom;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the other kiosks running this plugin with the same intercom key, so nobody has to type addresses. A UDP
 * broadcast on the call port asks who is there and every kiosk answers with its name. Messages carry a fingerprint
 * of the key (an HMAC, not the key), so kiosks with another key stay out of the list. Only a name and a fingerprint
 * cross the network; calling still goes through CallLink's handshake.
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

    private static final String PREFIX = "VI1|";
    private static final long FORGET_MILLIS = 5 * 60_000;

    private final int port;
    private final String fingerprint;
    private final Names names;
    private final Map<String, Kiosk> kiosks = new LinkedHashMap<>();
    private volatile DatagramSocket socket;
    private volatile boolean running = true;
    private final Thread thread;

    Discovery(int port, byte[] key, Names names) {
        this.port = port;
        this.names = names;
        byte[] mac = CallLink.mac(key, "discover", new byte[0], new byte[0]);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 8; i++) hex.append(String.format("%02x", mac[i] & 0xFF));
        fingerprint = hex.toString();
        thread = new Thread(this::run, "vi-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    /** Asks every kiosk on the network to answer. */
    void probe() {
        send("Q", null);
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
            send("A", null);  // say hello at start, so the others know this kiosk without asking
            byte[] buffer = new byte[512];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                s.receive(packet);
                handle(packet);
            }
        } catch (Throwable stopped) {
            // Closed, or the port is taken: calls still work, only the automatic list stays empty.
        }
    }

    private void handle(DatagramPacket packet) {
        String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
        if (!text.startsWith(PREFIX)) return;
        String[] parts = text.substring(PREFIX.length()).split("\\|", 3);
        if (parts.length < 3 || !parts[0].equals(fingerprint)) return;
        InetAddress from = packet.getAddress();
        if (isOwn(from)) return;
        String host = from.getHostAddress();
        String name = parts[2].trim();
        if (name.isEmpty()) name = host;
        if (name.length() > 80) name = name.substring(0, 80);
        synchronized (this) {
            kiosks.put(host, new Kiosk(name, host, System.currentTimeMillis()));
        }
        if (parts[1].equals("Q")) send("A", from);
    }

    /** Sends to one kiosk, or to every broadcast address of this kiosk's networks. */
    private void send(String kind, InetAddress to) {
        DatagramSocket s = socket;
        if (s == null) return;
        byte[] data = (PREFIX + fingerprint + "|" + kind + "|" + names.myName()).getBytes(StandardCharsets.UTF_8);
        try {
            if (to != null) {
                s.send(new DatagramPacket(data, data.length, to, port));
                return;
            }
            Set<InetAddress> targets = new HashSet<>();
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null) targets.add(address.getBroadcast());
                }
            }
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (InetAddress target : targets) {
                try {
                    s.send(new DatagramPacket(data, data.length, target, port));
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
