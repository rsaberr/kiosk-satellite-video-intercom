// SPDX-License-Identifier: Apache-2.0
package videointercom;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Runs the call protocol over loopback: ring, accept, voice both ways, bye; a second call that reuses the derived key;
 * a long name that arrives cut short; a caller with the wrong key. Then discovery: two kiosks find each other, and
 * answers with the wrong key, to a question never asked, or with a changed name are ignored.
 */
public final class CallLinkTest {
    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        KeyRing callee = new KeyRing("correct horse");
        callee.own();
        System.out.println("deriving a key took " + (System.nanoTime() - started) / 1_000_000 + " ms at " + KeyRing.ITERATIONS + " iterations");
        KeyRing caller = new KeyRing("  correct horse ");
        try (ServerSocket server = new ServerSocket(0)) {
            call(server, callee, caller, "Bedroom", "Bedroom");
            check(caller.derivations() == 1, "the caller derived the callee's key once");
            String longName = (char) 7 + "Hall" + repeat('x', 200);
            call(server, callee, caller, longName, "Hall" + repeat('x', CallLink.MAX_NAME - 4));
            check(caller.derivations() == 1, "the second call reused the derived key");

            // A caller with another key is refused and learns why.
            ArrayBlockingQueue<Object> events = new ArrayBlockingQueue<>(10);
            Thread guard = new Thread(() -> {
                try { CallLink.answer(server.accept(), callee); } catch (Exception expected) { events.add("rejected"); }
            });
            guard.start();
            try {
                CallLink.dial("127.0.0.1", server.getLocalPort(), new KeyRing("wrong"), "Intruder");
                check(false, "wrong key was let in");
            } catch (CallLink.Refused refused) {
                check(refused.reason.equals("key"), "refused for the key: " + refused.reason);
            }
            check("rejected".equals(events.poll(5, TimeUnit.SECONDS)), "callee rejected the wrong key");
        }
        check(CallLink.cleanName(" a\nb" + (char) 0x2028 + "c ").equals("abc"), "control characters are dropped from names");
        String smiley = new String(Character.toChars(0x1F600));
        check(CallLink.cleanName(repeat('x', 79) + smiley).length() == 79, "a name is not cut inside a character");

        findEachOther();
        ignoreBadAnswers();
        System.out.println("CallLink: all checks passed");
    }

    /** One whole call from caller to callee, checking the names both sides see. */
    private static void call(ServerSocket server, KeyRing calleeKeys, KeyRing callerKeys, String callerName, String expected) throws Exception {
        ArrayBlockingQueue<Object> calleeEvents = new ArrayBlockingQueue<>(10);
        Thread callee = new Thread(() -> {
            try {
                Socket s = server.accept();
                CallLink link = CallLink.answer(s, calleeKeys);
                calleeEvents.add("hello from " + link.peerName);
                link.ring("Office");
                link.start(new CallLink.Listener() {
                    @Override public void onFrame(CallLink l, int type, byte[] payload) {
                        calleeEvents.add("frame " + type + " " + payload.length);
                        if (type == CallLink.AUDIO) l.sendQuietly(CallLink.AUDIO, new byte[640]);
                    }
                    @Override public void onClosed(CallLink l, String reason) { calleeEvents.add("closed " + reason); }
                });
                link.sendQuietly(CallLink.ACCEPT, new byte[0]);
            } catch (Exception e) {
                calleeEvents.add(e);
            }
        });
        callee.start();
        CallLink caller = CallLink.dial("127.0.0.1", server.getLocalPort(), callerKeys, callerName);
        check(caller.peerName.equals("Office"), "callee name " + caller.peerName);
        Object hello = calleeEvents.poll(5, TimeUnit.SECONDS);
        check(("hello from " + expected).equals(hello), "callee saw the caller's name, at most " + CallLink.MAX_NAME + " characters");
        ArrayBlockingQueue<String> callerEvents = new ArrayBlockingQueue<>(10);
        caller.start(new CallLink.Listener() {
            @Override public void onFrame(CallLink l, int type, byte[] payload) { callerEvents.add("frame " + type + " " + payload.length); }
            @Override public void onClosed(CallLink l, String reason) { callerEvents.add("closed " + reason); }
        });
        check(("frame " + CallLink.ACCEPT + " 0").equals(callerEvents.poll(2, TimeUnit.SECONDS)), "accept reached caller");
        caller.send(CallLink.AUDIO, new byte[640], 640);
        check(("frame " + CallLink.AUDIO + " 640").equals(calleeEvents.poll(2, TimeUnit.SECONDS)), "audio reached callee");
        check(("frame " + CallLink.AUDIO + " 640").equals(callerEvents.poll(2, TimeUnit.SECONDS)), "audio reached caller");
        caller.sendQuietly(CallLink.BYE, new byte[0]);
        caller.close();
        check(("frame " + CallLink.BYE + " 0").equals(calleeEvents.poll(2, TimeUnit.SECONDS)), "bye reached callee");
        Object closed = calleeEvents.poll(2, TimeUnit.SECONDS);
        check(closed != null && closed.toString().startsWith("closed"), "callee link closed: " + closed);
    }

    /** Two kiosks with the same key, each sending its questions to the other's port, list each other. */
    private static void findEachOther() throws Exception {
        int first = freePort(), second = freePort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        Discovery a = new Discovery(first, new KeyRing("correct horse"), () -> "Office", new InetSocketAddress(loopback, second));
        Discovery b = new Discovery(second, new KeyRing("correct horse"), () -> "Hall", new InetSocketAddress(loopback, first));
        try {
            check(waitFor(a, "Hall") && waitFor(b, "Office"), "two kiosks found each other");
        } finally {
            a.stop();
            b.stop();
        }
    }

    /** A fake kiosk on a plain socket answers a real one's question in several wrong ways, then rightly. */
    private static void ignoreBadAnswers() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket fake = new DatagramSocket(0, loopback)) {
            fake.setSoTimeout(5000);
            int port = freePort();
            Discovery kiosk = new Discovery(port, new KeyRing("correct horse"), () -> "Office", new InetSocketAddress(loopback, fake.getLocalPort()));
            try {
                byte[] buffer = new byte[512];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                fake.receive(packet);
                String question = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                check(question.startsWith("VI2|Q|") && question.length() == 6 + 32, "a question carries only a nonce: " + question);
                byte[] nonce = KeyRing.unhex(question.substring(6), 16);
                InetSocketAddress to = new InetSocketAddress(loopback, port);

                KeyRing wrong = new KeyRing("wrong");
                send(fake, to, Discovery.answerText(wrong.own(), wrong.salt, nonce, "Wrong key"));
                KeyRing right = new KeyRing("correct horse");
                byte[] other = new byte[16];
                other[0] = 1;
                send(fake, to, Discovery.answerText(right.own(), right.salt, other, "Never asked"));
                String good = Discovery.answerText(right.own(), right.salt, nonce, "Hall");
                send(fake, to, good.replace("|Hall", "|Mallory"));
                Thread.sleep(500);
                check(kiosk.kiosks().isEmpty(), "answers with the wrong key, to another question or with a changed name are ignored");

                send(fake, to, good);
                check(waitFor(kiosk, "Hall"), "the right answer to the question is taken");
            } finally {
                kiosk.stop();
            }
        }
    }

    private static boolean waitFor(Discovery discovery, String name) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<Discovery.Kiosk> found = discovery.kiosks();
            if (found.size() == 1 && found.get(0).name.equals(name)) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static void send(DatagramSocket socket, InetSocketAddress to, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length, to));
    }

    private static int freePort() throws Exception {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static String repeat(char c, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append(c);
        return text.toString();
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("ok - " + what);
    }
}
