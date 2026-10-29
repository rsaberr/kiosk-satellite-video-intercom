// SPDX-License-Identifier: Apache-2.0
package videointercom;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Runs the call protocol over loopback: ring, accept, voice both ways, bye; then a caller with the wrong key. */
public final class CallLinkTest {
    public static void main(String[] args) throws Exception {
        byte[] key = CallLink.keyBytes("correct horse");
        try (ServerSocket server = new ServerSocket(0)) {
            ArrayBlockingQueue<Object> calleeEvents = new ArrayBlockingQueue<>(10);
            Thread callee = new Thread(() -> {
                try {
                    Socket s = server.accept();
                    CallLink link = CallLink.answer(s, key);
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
            CallLink caller = CallLink.dial("127.0.0.1", server.getLocalPort(), key, "Bedroom");
            check(caller.peerName.equals("Office"), "callee name " + caller.peerName);
            check("hello from Bedroom".equals(calleeEvents.poll(2, TimeUnit.SECONDS)), "callee saw caller");
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

            // A caller with another key is refused and learns why.
            Thread guard = new Thread(() -> {
                try { CallLink.answer(server.accept(), key); } catch (Exception expected) { calleeEvents.add("rejected"); }
            });
            guard.start();
            try {
                CallLink.dial("127.0.0.1", server.getLocalPort(), CallLink.keyBytes("wrong"), "Intruder");
                check(false, "wrong key was let in");
            } catch (CallLink.Refused refused) {
                check(refused.reason.equals("key"), "refused for the key: " + refused.reason);
            }
            check("rejected".equals(calleeEvents.poll(2, TimeUnit.SECONDS)), "callee rejected the wrong key");

        }
        System.out.println("CallLink: all checks passed");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        System.out.println("ok - " + what);
    }
}
