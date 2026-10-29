// SPDX-License-Identifier: Apache-2.0
package videointercom;

import android.content.Context;
import android.view.Surface;
import android.view.View;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Video calls between kiosks. Each kiosk listens for calls on its own port. A call is one TCP link between the two
 * plugins that carries the signalling and the voice both ways (see CallLink); the picture is the other kiosk's own
 * camera stream, which Kiosk Satellite already serves over RTSP. The person being called sees the caller as it rings;
 * the caller sees the other side only once it answers. The Video call screen previews this kiosk's own camera, from
 * its own stream, next to the kiosks that can be called.
 */
public final class VideoIntercomPlugin implements KioskPlugin {
    private enum Mode { IDLE, OUTGOING, INCOMING, IN_CALL, ENDING }

    private static final class Peer {
        final String name, host;
        final int port;

        Peer(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    private static final class Settings {
        String key = "", name = "", rtspPath = "/camera", rtspUser = "", rtspPassword = "";
        String answerMode = "Ring", microphone = "Automatic", micRate = "Automatic";
        String endGesture = "Double-tap the video", hangupKey = "Off";
        int port = 2391, rtspPort = 8080, ringSeconds = 30, callVolume = 80, ringVolume = 70;
        boolean muteWakeWord = true, selfView = true;
        final List<Peer> peers = new ArrayList<>();
    }

    private PluginHost host;
    private volatile Settings settings = new Settings();
    private String deviceName = "";
    private ScheduledExecutorService timers;
    /** Runs what the screen's buttons ask for: Android forbids network work on its main thread. */
    private ExecutorService work;

    // Finding the other kiosks. The key material is replaced, never changed, when the key changes; null without a key.
    private volatile KeyRing keys;
    private Discovery discovery;
    private String discoveryKey;
    private int discoveryPort;

    // Listening for calls.
    private ServerSocket server;
    private Thread acceptor;
    private int serverPort;
    /** Runs the handshakes of incoming connections, a few at a time; replaced with the server. */
    private ThreadPoolExecutor handshakes;
    /** The connections whose handshake is still running, with the callId they started under. Guarded by this. */
    private final Map<Socket, Integer> pending = new HashMap<>();
    /** Recent failed handshakes per address, so a host guessing the key is held off. Guarded by this. */
    private final Map<String, Strikes> strikes = new LinkedHashMap<String, Strikes>(16, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Strikes> eldest) {
            return size() > MAX_TRACKED_HOSTS;
        }
    };
    /** Connections turned away since the last log line about them, so a flood does not flood the log. Guarded by this. */
    private int dropped;
    private long droppedLogged;

    /** How many handshakes run at once, and how many of them may come from one address. */
    private static final int MAX_HANDSHAKES = 4, MAX_HANDSHAKES_PER_HOST = 2;
    /** How long a caller has to prove the key and be rung or refused, including a slow key derivation on its side. */
    private static final long HANDSHAKE_MILLIS = 5000;
    /** An address that fails this many handshakes within the window is turned away for the rest of the block time. */
    private static final int MAX_FAILURES = 5;
    private static final long FAILURE_WINDOW_MILLIS = 60_000, BLOCK_MILLIS = 60_000;
    private static final int MAX_TRACKED_HOSTS = 64;
    /** A key shorter than this is refused, because a short key can be guessed. */
    private static final int MIN_KEY = 16;

    /** Failed handshakes from one address. */
    private static final class Strikes {
        long since, blockedUntil;
        int count;
    }

    // The call. Guarded by this.
    private Mode mode = Mode.IDLE;
    private int callId;
    private CallLink link;
    private String peerName = "";
    private String statusText = "";
    /** What the other kiosk's picture says while it has none; written by the feed without the lock. */
    private volatile String videoText;
    private boolean muted;
    private Ringer ringer;
    private MicCapture mic;
    private PcmPlayer player;
    private VideoFeed peerFeed;
    private ScheduledFuture<?> timeout;
    private ScheduledFuture<?> keepalive;

    // This kiosk's own camera, for the preview and the corner of the call. Guarded by this.
    private VideoFeed selfFeed;
    private volatile String selfText;
    private volatile String myIp;
    private boolean homeOpen;
    private int homeId;
    private List<Peer> homePeers = new ArrayList<>();

    // The surfaces the feeds draw on, set on the main thread.
    private Surface peerSurface, selfSurface;

    // The views, on the main thread.
    private volatile CallView view;
    private volatile HomeView home;
    private volatile Context context;
    private CallMode callMode;
    private WakeWordGuard wakeWord;
    /** The hidden setting that remembers that this plugin muted the wake word, until it unmutes it again. */
    private static final String WAKE_WORD_RESTORE = "wakeWordRestore";

    // Kiosk Satellite's own intercom, which uses the same microphone and speaker. Guarded by this.
    private String intercomState = "idle";
    private boolean intercomKnown;  // a read or an event has told intercomState
    private boolean intercomFollowed;  // the intercom.state event keeps intercomState fresh
    private int intercomEvents;  // counts events, so an answer to an older read does not overwrite a newer event
    /** The intercom states with a call or an announcement on, ringing included: this plugin is busy then. */
    private static final List<String> INTERCOM_BUSY = Arrays.asList("calling", "ringing", "in_call", "broadcasting", "listening");
    /** The intercom states that use this kiosk's microphone: a call of this plugin ends for them. */
    private static final List<String> INTERCOM_TAKES_OVER = Arrays.asList("in_call", "broadcasting");
    /** How long a call waits for the intercom's state when the event cannot be trusted alone. */
    private static final long INTERCOM_READ_MILLIS = 1000;

    @Override public void start(PluginHost host, Map<String, Object> values) {
        this.host = host;
        timers = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "vi-timers");
            thread.setDaemon(true);
            return thread;
        });
        work = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "vi-work");
            thread.setDaemon(true);
            return thread;
        });
        wakeWord = new WakeWordGuard(new WakeWordGuard.Host() {
            @Override public void command(String name, Map<String, Object> arguments, WakeWordGuard.Result result) {
                try {
                    host.executeCommand(name, arguments, (ok, data, error) -> result.done(ok, data));
                } catch (RuntimeException unavailable) {
                    result.done(false, null);  // an older Kiosk Satellite, or the session ended
                }
            }

            @Override public void remember(boolean mutedByUs) {
                try {
                    host.updateSettings(Collections.<String, Object>singletonMap(WAKE_WORD_RESTORE, mutedByUs));
                } catch (RuntimeException | LinkageError unavailable) {
                    trace("cannot remember the wake word mute: " + unavailable);
                }
            }
        });
        // A previous session that muted the wake word and was stopped mid-call gets it back now. A call that starts
        // before the unmute is answered waits for it and then mutes again.
        wakeWord.restore(Boolean.TRUE.equals(values.get(WAKE_WORD_RESTORE)));
        settings = parse(values);
        publishState(idleState());
        host.executeCommand("getDeviceInfo", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            if (ok && data instanceof Map) {
                Object name = ((Map<?, ?>) data).get("name");
                if (name instanceof String) deviceName = (String) name;
                Object ip = ((Map<?, ?>) data).get("ip");
                if (ip instanceof String) myIp = (String) ip;
            }
        });
        listen();
        discover();
        try {
            host.subscribe("device.key");
        } catch (RuntimeException olderHost) {
            // Hanging up with a button needs a newer Kiosk Satellite.
        }
        followIntercom();
        timers.scheduleAtFixedRate(() -> {
            Discovery d = discovery;
            if (d != null) d.probe();
        }, 60, 60, TimeUnit.SECONDS);
    }

    @Override public void configure(Map<String, Object> values) {
        settings = parse(values);
        wakeWord.saved(Boolean.TRUE.equals(values.get(WAKE_WORD_RESTORE)));
        listen();
        discover();
        PcmPlayer p;
        synchronized (this) {
            p = player;
        }
        if (p != null) p.setVolume(settings.callVolume);
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        switch (command) {
            case "openMenu": showMenu(); break;
            default: throw new IllegalArgumentException("Unknown command " + command);
        }
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        if ("ks.intercom.state".equals(event)) {
            intercomChanged(payload);
            return;
        }
        if (!"ks.device.key".equals(event) || !"down".equals(payload.get("action"))) return;
        Object repeat = payload.get("repeat");
        if (repeat instanceof Number && ((Number) repeat).intValue() > 0) return;
        String wanted = settings.hangupKey;
        Object key = payload.get("key");
        boolean match = ("Volume up".equals(wanted) && "VOLUME_UP".equals(key)) || ("Volume down".equals(wanted) && "VOLUME_DOWN".equals(key));
        if (!match) return;
        synchronized (this) {
            if (mode != Mode.OUTGOING && mode != Mode.IN_CALL) return;
        }
        trace("hang-up button: " + key);
        hangup();
    }

    /** A gesture on the call's picture, from the main thread. */
    private void gesture(String kind) {
        String wanted = settings.endGesture;
        boolean match = ("double".equals(kind) && wanted.startsWith("Double")) || ("long".equals(kind) && wanted.startsWith("Long"));
        if (!match) return;
        synchronized (this) {
            if (mode != Mode.OUTGOING && mode != Mode.IN_CALL) return;
        }
        trace("end-call gesture: " + kind);
        hangup();
    }

    /**
     * Kiosk Satellite revokes the host before it calls this, so host calls here can fail and each step is released
     * whether they do or not. The wake word cannot be unmuted any more at this point: the remembered mute brings it
     * back at the next start.
     */
    @Override public void stop() {
        try {
            end(null, "stopped", 0);
        } catch (RuntimeException revoked) {
            // Hiding the call screen failed; everything else of the call is released before that.
        }
        closeServer();
        synchronized (this) {
            if (discovery != null) discovery.stop();
            discovery = null;
        }
        try {
            closeHome();
            host.hideOverlay("call");
        } catch (RuntimeException revoked) {
            // Kiosk Satellite removes the overlays of a stopped plugin itself.
        }
        updateSelfFeed();  // stops the own camera even when closing the preview failed above
        if (timers != null) timers.shutdownNow();
        if (work != null) work.shutdownNow();
    }

    /** Runs a step off the main thread; a failure is reported instead of taking Kiosk Satellite down. */
    private void run(String what, Runnable step) {
        try {
            work.execute(() -> {
                try {
                    step.run();
                } catch (Throwable error) {
                    trace(what + " failed: " + error);
                }
            });
        } catch (RuntimeException stopped) {
            // The plugin is stopping.
        }
    }

    /** One step of a call, in Kiosk Satellite's app log. */
    private void trace(String step) {
        try {
            host.log(step);
        } catch (Throwable ignored) {
            // Diagnostics are best effort.
        }
    }

    // ---- Finding kiosks ----

    /**
     * Makes new key material, with a new salt, when the key changed, and (re)starts discovery when the key or port
     * changed. Deriving this kiosk's own key takes a moment on a slow tablet, so it starts on the work thread instead
     * of here; a call that arrives before it is done waits for it on its own thread.
     */
    private synchronized void discover() {
        Settings s = settings;
        String key = s.key.trim();
        boolean newKey = !key.equals(discoveryKey);
        if (newKey) {
            discoveryKey = key;
            KeyRing ring = keyProblem(s) != null ? null : new KeyRing(key);
            keys = ring;
            if (ring != null) run("deriving the key", ring::own);
        }
        if (discovery != null && !newKey && s.port == discoveryPort) return;
        if (discovery != null) discovery.stop();
        discovery = null;
        discoveryPort = s.port;
        KeyRing ring = keys;
        if (ring == null) return;
        discovery = new Discovery(s.port, ring, this::myName);
    }

    /** The kiosks to offer: the ones typed under Kiosks first, then the ones found on the network. */
    private List<Peer> peers() {
        Settings s = settings;
        List<Peer> list = new ArrayList<>(s.peers);
        Discovery d = discovery;
        if (d != null) {
            for (Discovery.Kiosk kiosk : d.kiosks()) {
                boolean known = false;
                for (Peer peer : s.peers) if (peer.host.equals(kiosk.host)) known = true;
                if (!known) list.add(new Peer(kiosk.name, kiosk.host, s.port));
            }
        }
        return list;
    }

    // ---- Listening ----

    /**
     * Listens for calls while the key is usable. Without one, or with a short one, this kiosk takes no calls, makes
     * none and is not found: the status line and the Video call reading say why.
     */
    private synchronized void listen() {
        Settings s = settings;
        String problem = keyProblem(s);
        if (mode == Mode.IDLE) publishState(idleState());
        if (problem != null) {
            closeServer();
            host.status(problem, true);
            return;
        }
        String ready = "Ready on port " + s.port;
        if (server != null && serverPort == s.port) {
            host.status(ready, false);
            return;
        }
        closeServer();
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(s.port));
            ThreadPoolExecutor pool = new ThreadPoolExecutor(0, MAX_HANDSHAKES, 30, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(), r -> {
                        Thread thread = new Thread(r, "vi-incoming");
                        thread.setDaemon(true);
                        return thread;
                    });
            server = socket;
            serverPort = s.port;
            handshakes = pool;
            Thread thread = new Thread(() -> acceptLoop(socket, pool), "vi-server");
            thread.setDaemon(true);
            acceptor = thread;
            try {
                thread.start();
            } catch (Throwable noThread) {
                closeServer();
                host.status("Cannot listen on port " + s.port + ": " + noThread, true);
                return;
            }
            host.status(ready, false);
        } catch (IOException error) {
            host.status("Cannot listen on port " + s.port + ": " + error.getMessage(), true);
        }
    }

    /** Stops listening and drops the handshakes that are still running; calls already rung keep going. */
    private synchronized void closeServer() {
        if (server != null) {
            try { server.close(); } catch (IOException ignored) {}
        }
        if (handshakes != null) handshakes.shutdownNow();
        for (Map.Entry<Socket, Integer> entry : pending.entrySet()) {
            if (entry.getValue() == callId) closeQuietly(entry.getKey());
        }
        server = null;
        acceptor = null;
        serverPort = 0;
        handshakes = null;
    }

    /**
     * Accepts connections until the server socket is closed, and nothing else ends it: a connection that cannot be
     * handed to a handshake thread is closed, and a failing accept is retried after a short pause.
     */
    private void acceptLoop(ServerSocket socket, ThreadPoolExecutor pool) {
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (Throwable error) {
                if (socket.isClosed()) return;
                // Out of file descriptors, say: waiting a little keeps the loop from spinning.
                dropped("accepting a call failed: " + error);
                pause(500);
                continue;
            }
            try {
                dispatch(socket, pool, client);
            } catch (Throwable error) {
                // No thread to spare: this caller can try again.
                synchronized (this) {
                    pending.remove(client);
                }
                closeQuietly(client);
                dropped("turned away a connection: " + error);
            }
        }
    }

    /** Hands a new connection to a handshake thread, or closes it when the address is held off or too busy. */
    private void dispatch(ServerSocket socket, ThreadPoolExecutor pool, Socket client) {
        String address = client.getInetAddress().getHostAddress();
        String refused;
        int startId = 0;
        synchronized (this) {
            if (socket != server || pool != handshakes) {
                closeQuietly(client);
                return;
            }
            refused = admit(address);
            if (refused == null) {
                startId = callId;
                pending.put(client, startId);
            }
        }
        if (refused != null) {
            closeQuietly(client);
            dropped("turned away a connection " + refused + " " + address);
            return;
        }
        int id = startId;
        pool.execute(() -> incoming(client, address, id));
    }

    /** Why a connection from this address is turned away, or null to take it. Called with the lock held. */
    private String admit(String address) {
        Strikes s = strikes.get(address);
        if (s != null && s.blockedUntil != 0 && s.blockedUntil - now() > 0) return "after repeated failed calls from";
        if (pending.size() >= MAX_HANDSHAKES) return "while busy with other connections, from";
        int fromHere = 0;
        for (Socket other : pending.keySet()) {
            if (address.equals(other.getInetAddress().getHostAddress())) fromHere++;
        }
        return fromHere >= MAX_HANDSHAKES_PER_HOST ? "with too many connections at once from" : null;
    }

    /** Runs one handshake on a handshake thread, with a deadline for the caller to be rung or refused. */
    private void incoming(Socket socket, String address, int startId) {
        ScheduledFuture<?> watchdog;
        try {
            watchdog = timers.schedule(() -> expire(socket), HANDSHAKE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException stopping) {
            synchronized (this) {
                pending.remove(socket);
            }
            closeQuietly(socket);
            return;
        }
        boolean proved = false;
        try {
            proved = takeCall(socket);
        } catch (Throwable error) {
            trace("incoming call failed: " + error);
        } finally {
            watchdog.cancel(false);
            finished(socket, address, startId, proved);
        }
    }

    /**
     * Closes a connection whose handshake ran past the deadline. Ringing raises callId under the same lock, so a
     * connection that has become a call is never closed here.
     */
    private synchronized void expire(Socket socket) {
        Integer started = pending.get(socket);
        if (started != null && started == callId) closeQuietly(socket);
    }

    /**
     * Ends the bookkeeping of a handshake. A caller that did not prove the key counts against its address: a wrong
     * key, garbage, or a caller that ran out of time. A caller turned away as busy or by Do not disturb does not.
     */
    private void finished(Socket socket, String address, int startId, boolean proved) {
        String held = null;
        synchronized (this) {
            pending.remove(socket);
            if (callId != startId) return;
            closeQuietly(socket);
            if (proved) return;
            long now = now();
            Strikes s = strikes.get(address);
            if (s == null || now - s.since > FAILURE_WINDOW_MILLIS) {
                strikes.remove(address);
                s = new Strikes();
                s.since = now;
                strikes.put(address, s);
            }
            if (++s.count >= MAX_FAILURES) {
                s.blockedUntil = now + BLOCK_MILLIS;
                if (s.blockedUntil == 0) s.blockedUntil = 1;
                s.count = 0;
                s.since = now;
                held = address;
            }
        }
        if (held != null) trace("holding off " + held + " for a minute after " + MAX_FAILURES + " failed calls");
    }

    /** Logs a turned-away connection, at most once a minute with a count of the others. */
    private void dropped(String what) {
        String line = null;
        synchronized (this) {
            dropped++;
            long now = now();
            if (droppedLogged == 0 || now - droppedLogged >= 60_000) {
                line = dropped > 1 ? what + " (" + dropped + " turned away since the last note)" : what;
                dropped = 0;
                droppedLogged = now == 0 ? 1 : now;
            }
        }
        if (line != null) trace(line);
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            // The loop checks the socket again.
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    /** Answers a connection: rings, or refuses it. Returns whether the caller proved the key. */
    private boolean takeCall(Socket socket) {
        CallLink candidate;
        try {
            candidate = CallLink.answer(socket, keys);
        } catch (IOException refused) {
            host.log("refused a call: " + refused.getMessage());
            return false;
        }
        Settings s = settings;
        // A call on Kiosk Satellite's own intercom holds the microphone and the speaker: this kiosk is busy then.
        boolean intercom = intercomBusy(INTERCOM_READ_MILLIS);
        int id;
        synchronized (this) {
            if (mode != Mode.IDLE || intercom) {
                if (intercom) trace("refused a call from " + candidate.peerName + ": the intercom is busy");
                candidate.refuse("busy");
                return true;
            }
            if ("Do not disturb".equals(s.answerMode)) {
                candidate.refuse("dnd");
                return true;
            }
            try {
                candidate.ring(myName());
            } catch (IOException gone) {
                candidate.close();
                return true;
            }
            id = ++callId;
            mode = Mode.INCOMING;
            link = candidate;
            peerName = candidate.peerName;
            muted = false;
            statusText = "Video call";
            videoText = "Connecting video…";
            boolean auto = "Answer automatically".equals(s.answerMode);
            if (!auto) ringer = new Ringer(s.ringVolume);
            candidate.start(listener);
            trace("ringing from " + peerName + " at " + candidate.peerHost);
            startPeerVideo(candidate.peerHost);
            timeout = timers.schedule(() -> noAnswer(id), s.ringSeconds, TimeUnit.SECONDS);
            keepalive = timers.scheduleAtFixedRate(() -> keepalive(id), 3, 3, TimeUnit.SECONDS);
            if (auto) {
                statusText = "Answering…";
                timers.schedule(() -> autoAnswer(id), 3, TimeUnit.SECONDS);
            }
        }
        wake();
        try {
            host.fireTrigger("incomingCall");
        } catch (RuntimeException olderHost) {
            // Gesture triggers need a newer Kiosk Satellite.
        }
        publishState("Ringing from " + peerName);
        closeHome();
        updateSelfFeed();
        showCall();
        return true;
    }

    // ---- Placing and taking calls ----

    private void call(Peer peer) {
        Settings s = settings;
        String problem = keyProblem(s);
        boolean intercom = problem == null && intercomBusy(INTERCOM_READ_MILLIS);
        int id;
        synchronized (this) {
            if (mode != Mode.IDLE) return;
            id = ++callId;
            mode = Mode.OUTGOING;
            peerName = peer.name;
            muted = false;
            statusText = "Calling…";
            videoText = null;
        }
        closeHome();
        publishState("Calling " + peer.name);
        updateSelfFeed();
        showCall();
        if (problem != null) {
            // An Extra kiosk can still be listed: the call screen says why it is not called.
            trace("not calling " + peer.name + ": " + problem);
            end(id, s.key.trim().isEmpty() ? "Set an Intercom key in the plugin settings" : "Set a longer Intercom key in the plugin settings", 3000);
            return;
        }
        if (intercom) {
            // The call screen says why for a moment, the same as when the other kiosk is busy.
            trace("not calling " + peer.name + ": the intercom is busy");
            end(id, "Busy in an intercom call", 3000);
            return;
        }
        Thread thread = new Thread(() -> dial(id, peer), "vi-dial");
        thread.setDaemon(true);
        thread.start();
    }

    private void dial(int id, Peer peer) {
        try {
            dialNow(id, peer);
        } catch (Throwable error) {
            trace("call failed: " + error);
            end(id, "Call failed", 3000);
        }
    }

    private void dialNow(int id, Peer peer) {
        Settings s = settings;
        CallLink dialled;
        try {
            dialled = CallLink.dial(peer.host, peer.port, keys, myName());
        } catch (CallLink.Refused refused) {
            end(id, refusal(refused.reason), 3000);
            return;
        } catch (IOException error) {
            end(id, "Could not reach " + peer.name, 3000);
            host.log("call to " + peer.host + ":" + peer.port + " failed: " + error.getMessage());
            return;
        }
        synchronized (this) {
            if (callId != id || mode != Mode.OUTGOING) {
                dialled.close();
                return;
            }
            link = dialled;
            statusText = "Ringing…";
            dialled.start(listener);
            timeout = timers.schedule(() -> end(id, "No answer", 3000), s.ringSeconds + 5, TimeUnit.SECONDS);
            keepalive = timers.scheduleAtFixedRate(() -> keepalive(id), 3, 3, TimeUnit.SECONDS);
        }
        refreshCall();
    }

    private void answer() {
        CallLink l;
        synchronized (this) {
            if (mode != Mode.INCOMING || link == null) return;
            l = link;
            trace("answering");
            l.sendQuietly(CallLink.ACCEPT, new byte[0]);
            connected();
        }
        refreshCall();
    }

    private void autoAnswer(int id) {
        synchronized (this) {
            if (callId != id) return;
        }
        answer();
    }

    private void noAnswer(int id) {
        CallLink l;
        synchronized (this) {
            if (callId != id || mode != Mode.INCOMING) return;
            l = link;
        }
        if (l != null) l.refuse("no answer");
        end(id, "Missed call", 2000);
    }

    private void decline() {
        CallLink l;
        int id;
        synchronized (this) {
            if (mode != Mode.INCOMING) return;
            l = link;
            id = callId;
        }
        if (l != null) l.refuse("declined");
        end(id, "Declined", 0);
    }

    private void hangupAny() {
        Mode m;
        synchronized (this) {
            m = mode;
        }
        if (m == Mode.INCOMING) decline();
        else hangup();
    }

    private void hangup() {
        CallLink l;
        int id;
        synchronized (this) {
            if (mode == Mode.IDLE || mode == Mode.ENDING) return;
            l = link;
            id = callId;
        }
        if (l != null) {
            l.sendQuietly(CallLink.BYE, new byte[0]);
            l.close();
        }
        end(id, "Call ended", 1500);
    }

    private void toggleMute() {
        synchronized (this) {
            if (mode != Mode.IN_CALL) return;
            muted = !muted;
        }
        refreshCall();
    }

    /** Both sides are in the call: voice on, and the caller now sees the other kiosk. Holds the lock. */
    private void connected() {
        Settings s = settings;
        mode = Mode.IN_CALL;
        statusText = "In call";
        if (ringer != null) {
            ringer.stop();
            ringer = null;
        }
        if (timeout != null) {
            timeout.cancel(false);
            timeout = null;
        }
        if (peerFeed == null && link != null) startPeerVideo(link.peerHost);
        Context c = context;
        if (c != null) {
            if (callMode == null) callMode = new CallMode(c);
            String route = callMode.enter();
            if (route != null) host.log(route);
        }
        if (s.muteWakeWord) wakeWord.setActive(true);
        player = new PcmPlayer(s.callVolume);
        mic = new MicCapture(new LinkTalk(link), s.microphone, s.micRate, state -> trace("microphone: " + state));
        trace("in call with " + peerName);
        publishState("In call with " + peerName);
    }

    /**
     * Ends the call with id (null for whatever call is on), shows reason for a moment if linger is above zero, then
     * closes the call screen. The work that waits for threads happens outside the lock.
     */
    private void end(Integer id, String reason, int lingerMillis) {
        Ringer ring;
        MicCapture m;
        PcmPlayer p;
        VideoFeed feed;
        CallLink l;
        int current;
        boolean inCall;
        synchronized (this) {
            if (id != null && id != callId) return;
            if (mode == Mode.IDLE) return;
            inCall = mode == Mode.IN_CALL;
            current = callId;
            mode = lingerMillis > 0 ? Mode.ENDING : Mode.IDLE;
            statusText = reason;
            videoText = null;
            ring = ringer;
            ringer = null;
            m = mic;
            mic = null;
            p = player;
            player = null;
            feed = peerFeed;
            peerFeed = null;
            l = link;
            link = null;
            if (timeout != null) timeout.cancel(false);
            if (keepalive != null) keepalive.cancel(false);
            timeout = null;
            keepalive = null;
        }
        if (ring != null) ring.stop();
        if (l != null) l.close();
        if (m != null) m.release();
        if (p != null) p.release();
        if (feed != null) feed.stop();
        updateSelfFeed();
        leaveCallMode();
        if (inCall) wakeWord.setActive(false);
        publishState(idleState());
        if (lingerMillis > 0) {
            refreshCall();
            timers.schedule(() -> closeAfter(current), lingerMillis, TimeUnit.MILLISECONDS);
        } else {
            host.hideOverlay("call");
        }
    }

    private void closeAfter(int id) {
        synchronized (this) {
            if (callId != id || mode != Mode.ENDING) return;
            mode = Mode.IDLE;
        }
        host.hideOverlay("call");
    }

    private void keepalive(int id) {
        CallLink l;
        synchronized (this) {
            if (callId != id) return;
            l = link;
        }
        if (l != null) l.sendQuietly(CallLink.PING, new byte[0]);
        // A call keeps the screen awake, the way Kiosk Satellite's own intercom holds the screensaver.
        host.executeCommand("postponeScreensaver", Collections.<String, Object>emptyMap(), (ok, data, error) -> {});
    }

    private final CallLink.Listener listener = new CallLink.Listener() {
        @Override public void onFrame(CallLink from, int type, byte[] payload) {
            PcmPlayer p = null;
            boolean refresh = false;
            int id;
            synchronized (VideoIntercomPlugin.this) {
                if (from != link) return;
                id = callId;
                switch (type) {
                    case CallLink.AUDIO:
                        if (mode == Mode.IN_CALL) p = player;
                        break;
                    case CallLink.ACCEPT:
                        if (mode == Mode.OUTGOING) {
                            trace("answered by " + peerName);
                            connected();
                            refresh = true;
                        }
                        break;
                    default:
                        break;
                }
            }
            if (p != null) p.write(payload);
            if (refresh) refreshCall();
            if (type == CallLink.BYE) end(id, "Call ended", 1500);
            else if (type == CallLink.REFUSED) end(id, refusal(new String(payload, java.nio.charset.StandardCharsets.UTF_8)), 3000);
        }

        @Override public void onClosed(CallLink from, String reason) {
            int id;
            Mode m;
            synchronized (VideoIntercomPlugin.this) {
                if (from != link) return;
                id = callId;
                m = mode;
            }
            if (reason != null) host.log("call link closed: " + reason);
            end(id, m == Mode.INCOMING ? "Missed call" : "Call ended", 2000);
        }
    };

    /** The microphone's audio, in 20 ms frames on the link; nothing goes out while muted. */
    private final class LinkTalk implements TalkChannel {
        private final CallLink out;
        private final byte[] frame = new byte[PcmPlayer.RATE / 50 * 2];
        private int filled;

        LinkTalk(CallLink out) {
            this.out = out;
        }

        @Override public void start() {}

        @Override public void stop() {}

        @Override public void write(short[] samples, int count) {
            boolean silent;
            synchronized (VideoIntercomPlugin.this) {
                silent = muted;
            }
            for (int i = 0; i < count; i++) {
                short value = silent ? 0 : samples[i];
                frame[filled++] = (byte) value;
                frame[filled++] = (byte) (value >> 8);
                if (filled == frame.length) {
                    filled = 0;
                    if (!silent) out.sendQuietly(CallLink.AUDIO, frame);
                }
            }
        }

        @Override public void clear() {
            filled = 0;
        }

        @Override public int rate() {
            return PcmPlayer.RATE;
        }
    }

    // ---- Video ----

    /** The ports to try for a kiosk's camera stream: the configured one, then Kiosk Satellite's two defaults. */
    private List<Integer> videoPorts() {
        List<Integer> ports = new ArrayList<>();
        for (int port : new int[] {settings.rtspPort, 8554, 8080}) if (!ports.contains(port)) ports.add(port);
        return ports;
    }

    /** Starts the other kiosk's camera on the call screen. Holds the lock; the feed never takes it. */
    private void startPeerVideo(String peerHost) {
        Settings s = settings;
        videoText = "Connecting video…";
        VideoFeed feed = new VideoFeed(peerHost, videoPorts(), s.rtspPath, s.rtspUser, s.rtspPassword, new VideoFeed.Listener() {
            @Override public void onMessage(String message) {
                videoText = message;
                refreshCall();
            }

            @Override public void onSize(int width, int height) {
                CallView v = view;
                if (v != null) v.post(() -> v.remote.setVideoSize(width, height));
            }

            @Override public void log(String line) {
                trace(line);
            }
        });
        peerFeed = feed;
        feed.start();
        Surface surface = peerSurface;
        if (surface != null) feed.setSurface(surface);
    }

    /**
     * Runs this kiosk's own camera while something shows it: the Video call screen, or a call with Show my camera
     * on. It reads the kiosk's own stream, the same one the other kiosks see.
     */
    private void updateSelfFeed() {
        VideoFeed stop = null;
        VideoFeed started = null;
        synchronized (this) {
            Settings s = settings;
            boolean calling = mode == Mode.OUTGOING || mode == Mode.INCOMING || mode == Mode.IN_CALL;
            boolean wanted = homeOpen || (calling && s.selfView);
            if (wanted && selfFeed == null) {
                String own = myIp != null ? myIp : "127.0.0.1";
                selfText = "Starting camera…";
                selfFeed = new VideoFeed(own, videoPorts(), s.rtspPath, s.rtspUser, s.rtspPassword, new VideoFeed.Listener() {
                    @Override public void onMessage(String message) {
                        selfText = message == null ? null : message.startsWith("No video") ? "No camera: is camera streaming on here?" : message;
                        refreshCall();
                        refreshHome();
                    }

                    @Override public void onSize(int width, int height) {
                        CallView v = view;
                        if (v != null) v.post(() -> v.self.setVideoSize(width, height));
                        HomeView h = home;
                        if (h != null) h.post(() -> h.self.setVideoSize(width, height));
                    }

                    @Override public void log(String line) {
                        trace("own camera: " + line);
                    }
                });
                started = selfFeed;
                started.start();
            } else if (!wanted && selfFeed != null) {
                stop = selfFeed;
                selfFeed = null;
            }
        }
        Surface surface = selfSurface;
        if (started != null && surface != null) started.setSurface(surface);
        if (stop != null) stop.stop();
    }

    /** Surface callbacks from the views, on the main thread. */
    private final VideoPane.SurfaceListener peerSurfaces = new VideoPane.SurfaceListener() {
        @Override public void ready(Surface surface) {
            try {
                peerSurface = surface;
                VideoFeed feed;
                synchronized (VideoIntercomPlugin.this) {
                    feed = peerFeed;
                }
                if (feed != null) feed.setSurface(surface);
            } catch (Throwable error) {
                trace("video surface failed: " + error);
            }
        }

        @Override public void gone(Surface surface) {
            try {
                if (peerSurface == surface) peerSurface = null;
                VideoFeed feed;
                synchronized (VideoIntercomPlugin.this) {
                    feed = peerFeed;
                }
                if (feed != null) feed.clearSurface(surface);
            } catch (Throwable error) {
                trace("video surface release failed: " + error);
            }
        }
    };

    private final VideoPane.SurfaceListener selfSurfaces = new VideoPane.SurfaceListener() {
        @Override public void ready(Surface surface) {
            try {
                selfSurface = surface;
                VideoFeed feed;
                synchronized (VideoIntercomPlugin.this) {
                    feed = selfFeed;
                }
                if (feed != null) feed.setSurface(surface);
            } catch (Throwable error) {
                trace("own camera surface failed: " + error);
            }
        }

        @Override public void gone(Surface surface) {
            try {
                if (selfSurface == surface) selfSurface = null;
                VideoFeed feed;
                synchronized (VideoIntercomPlugin.this) {
                    feed = selfFeed;
                }
                if (feed != null) feed.clearSurface(surface);
            } catch (Throwable error) {
                trace("own camera surface release failed: " + error);
            }
        }
    };

    // ---- Screens ----

    private void showCall() {
        host.showOverlay("call", OverlaySpec.fullScreen().onTop(true).closeOnBack(false).enter(OverlaySpec.FADE).exit(OverlaySpec.FADE),
            new OverlayFactory() {
                @Override public View create(Context c, KsTheme theme) {
                    context = c.getApplicationContext();
                    CallView created = new CallView(c, theme, new CallView.Callback() {
                        @Override public void answer() { run("answer", VideoIntercomPlugin.this::answer); }
                        @Override public void decline() { run("decline", VideoIntercomPlugin.this::decline); }
                        @Override public void hangup() { run("hang up", VideoIntercomPlugin.this::hangupAny); }
                        @Override public void toggleMute() { run("mute", VideoIntercomPlugin.this::toggleMute); }
                        @Override public void gesture(String kind) { run("gesture", () -> VideoIntercomPlugin.this.gesture(kind)); }
                    });
                    view = created;
                    created.remote.setSurfaceListener(peerSurfaces);
                    created.self.setSurfaceListener(selfSurfaces);
                    apply(created);
                    run("call mode", VideoIntercomPlugin.this::enterCallModeIfNeeded);
                    return created;
                }

                @Override public void onDestroy(View destroyed) {
                    if (view == destroyed) view = null;
                }
            });
    }

    /** A call answered before the screen existed (from Home Assistant) gets call mode once the screen has a context. */
    private synchronized void enterCallModeIfNeeded() {
        if (mode != Mode.IN_CALL || context == null) return;
        if (callMode == null) callMode = new CallMode(context);
        String route = callMode.enter();
        if (route != null) trace(route);
    }

    /**
     * Leaves call mode at the end of a call. While Kiosk Satellite's own intercom has something on, which started
     * under this plugin's call mode, the old mode comes back only once the intercom is done (see intercomChanged).
     */
    private void leaveCallMode() {
        CallMode c;
        synchronized (this) {
            c = callMode;
        }
        if (c == null) return;
        c.leave(intercomHoldsAudio());
        // The intercom may have finished between the two reads, before the leave above was waiting for it.
        if (!intercomHoldsAudio()) c.finishLeave();
    }

    // ---- Kiosk Satellite's own intercom ----

    /** Starts following Kiosk Satellite's intercom: the event keeps the state fresh, a read gives the first one. */
    private void followIntercom() {
        try {
            host.subscribe("intercom.state");
            synchronized (this) {
                intercomFollowed = true;
            }
        } catch (RuntimeException olderHost) {
            // Older Kiosk Satellite versions have no intercom event: each call reads the state instead.
        }
        readIntercom(null);
    }

    /**
     * Asks Kiosk Satellite for its intercom's state. done, if any, runs once the answer is in, or at once when the
     * command cannot be sent. An answer is dropped when an event arrived after the question, since the event is newer.
     */
    private void readIntercom(Runnable done) {
        int asked;
        synchronized (this) {
            asked = intercomEvents;
        }
        try {
            host.executeCommand("getIntercomState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                try {
                    if (ok && data instanceof Map) {
                        Map<?, ?> state = (Map<?, ?>) data;
                        // While the intercom is off it has no calls, whatever else the answer holds.
                        String value = Boolean.FALSE.equals(state.get("enabled")) ? "idle" : String.valueOf(state.get("state"));
                        boolean applied;
                        synchronized (VideoIntercomPlugin.this) {
                            applied = intercomEvents == asked;
                            if (applied) {
                                intercomState = value;
                                intercomKnown = true;
                            }
                        }
                        // The intercom being turned off sends no event, so a quiet answer also ends a wait for it.
                        if (applied && !INTERCOM_BUSY.contains(value)) finishCallModeLater();
                    }
                } finally {
                    if (done != null) done.run();
                }
            });
        } catch (RuntimeException unavailable) {
            if (done != null) done.run();
        }
    }

    /**
     * Whether Kiosk Satellite's intercom has a call or an announcement on, which makes this plugin busy. A quiet state
     * that the event keeps fresh is trusted as it is. Otherwise this asks again and waits up to waitMillis for the
     * answer, since no event says so when the intercom is turned off mid-call. A state that cannot be read counts as
     * quiet. It can block, so never call it holding the lock or on the host's worker.
     */
    private boolean intercomBusy(long waitMillis) {
        synchronized (this) {
            if (intercomFollowed && intercomKnown && !INTERCOM_BUSY.contains(intercomState)) return false;
        }
        CountDownLatch answered = new CountDownLatch(1);
        readIntercom(answered::countDown);
        try {
            answered.await(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            return intercomKnown && INTERCOM_BUSY.contains(intercomState);
        }
    }

    /** Whether the intercom, as the event last told, has something on that started under this plugin's call mode. */
    private synchronized boolean intercomHoldsAudio() {
        return intercomFollowed && intercomKnown && INTERCOM_BUSY.contains(intercomState);
    }

    /**
     * The intercom.state event, on the host's worker, so the work goes to the work thread. A call or an announcement
     * from this kiosk on the intercom ends this plugin's call, so the two do not capture the microphone at once: the
     * person just chose the intercom, or it answered by itself. A ring on the intercom leaves this call alone until
     * it is answered. Once the intercom is done, call mode that waited for it is left.
     */
    private void intercomChanged(Map<String, Object> payload) {
        String state = String.valueOf(payload.get("state"));
        int id;
        Mode m;
        synchronized (this) {
            intercomEvents++;
            intercomState = state;
            intercomKnown = true;
            id = callId;
            m = mode;
        }
        if (INTERCOM_TAKES_OVER.contains(state) && m != Mode.IDLE && m != Mode.ENDING) {
            run("intercom", () -> yieldToIntercom(id));
        } else if (!INTERCOM_BUSY.contains(state)) {
            finishCallModeLater();
        }
    }

    /** Leaves call mode, on the work thread, if the end of a call left it waiting for the intercom. */
    private void finishCallModeLater() {
        run("call mode", () -> {
            CallMode c;
            synchronized (VideoIntercomPlugin.this) {
                c = callMode;
            }
            if (c != null) c.finishLeave();
        });
    }

    /** Ends call id because the intercom took the microphone; the other kiosk hears that this one is busy. */
    private void yieldToIntercom(int id) {
        CallLink l;
        Mode m;
        synchronized (this) {
            if (callId != id || mode == Mode.IDLE || mode == Mode.ENDING) return;
            l = link;
            m = mode;
        }
        trace("ending the call: the intercom took over");
        if (l != null) {
            if (m == Mode.INCOMING) {
                l.refuse("busy");
            } else {
                l.sendQuietly(CallLink.BYE, new byte[0]);
                l.close();
            }
        }
        end(id, "Ended for an intercom call", 3000);
    }

    private void refreshCall() {
        CallView v = view;
        if (v != null) v.post(() -> {
            try {
                apply(v);
            } catch (Throwable error) {
                trace("call screen failed: " + error);
            }
        });
    }

    /** Shows the current state on the view. Main thread. */
    private void apply(CallView v) {
        String name, status;
        CallView.Buttons buttons;
        boolean m;
        synchronized (this) {
            name = peerName;
            status = statusText;
            m = muted;
            switch (mode) {
                case INCOMING: buttons = CallView.Buttons.INCOMING; break;
                case OUTGOING: buttons = CallView.Buttons.OUTGOING; break;
                case IN_CALL: buttons = CallView.Buttons.IN_CALL; break;
                default: buttons = CallView.Buttons.NONE; break;
            }
        }
        boolean showSelf = settings.selfView && buttons != CallView.Buttons.NONE;
        v.show(name, status, buttons, m, showSelf);
        v.remote.setMessage(videoText);
        v.self.setMessage(selfText);
    }

    /** Opens the Video call screen: this kiosk's camera and the kiosks to call. */
    private void showMenu() {
        int id;
        synchronized (this) {
            if (mode != Mode.IDLE) return;
            homeOpen = true;
            id = ++homeId;
            homePeers = peers();
        }
        updateSelfFeed();
        host.showOverlay("home", OverlaySpec.fullScreen().onTop(true).closeOnBack(true).enter(OverlaySpec.FADE).exit(OverlaySpec.FADE),
            new OverlayFactory() {
                @Override public View create(Context c, KsTheme theme) {
                    context = c.getApplicationContext();
                    HomeView created = new HomeView(c, theme, new HomeView.Callback() {
                        @Override public void call(int index) {
                            run("call", () -> {
                                Peer peer;
                                synchronized (VideoIntercomPlugin.this) {
                                    if (index < 0 || index >= homePeers.size()) return;
                                    peer = homePeers.get(index);
                                }
                                VideoIntercomPlugin.this.call(peer);
                            });
                        }

                        @Override public void close() {
                            run("close", VideoIntercomPlugin.this::closeHome);
                        }
                    });
                    home = created;
                    created.self.setSurfaceListener(selfSurfaces);
                    applyHome(created, true);
                    return created;
                }

                @Override public void onDestroy(View destroyed) {
                    if (home != destroyed) return;
                    home = null;
                    // Closed by back: stop the camera too.
                    run("close", () -> {
                        boolean wasOpen;
                        synchronized (VideoIntercomPlugin.this) {
                            wasOpen = homeOpen && homeId == id;
                            if (wasOpen) homeOpen = false;
                        }
                        if (wasOpen) updateSelfFeed();
                    });
                }
            });
        // Ask the network again and refresh the list as answers come in, for as long as the screen is open.
        Discovery d = discovery;
        if (d != null) d.probe();
        for (int delay : new int[] {700, 2000, 5000}) {
            timers.schedule(() -> refreshHomePeers(id), delay, TimeUnit.MILLISECONDS);
        }
        // A preview left open would keep the camera busy for nothing.
        timers.schedule(() -> {
            synchronized (VideoIntercomPlugin.this) {
                if (!homeOpen || homeId != id) return;
            }
            closeHome();
        }, 3, TimeUnit.MINUTES);
    }

    private void refreshHomePeers(int id) {
        synchronized (this) {
            if (!homeOpen || homeId != id) return;
            homePeers = peers();
        }
        HomeView h = home;
        if (h != null) h.post(() -> applyHome(h, false));
    }

    private void refreshHome() {
        HomeView h = home;
        if (h != null) h.post(() -> {
            try {
                h.self.setMessage(selfText);
            } catch (Throwable error) {
                trace("video call screen failed: " + error);
            }
        });
    }

    /** Main thread. */
    private void applyHome(HomeView h, boolean searching) {
        List<String> names = new ArrayList<>();
        synchronized (this) {
            for (Peer peer : homePeers) names.add(peer.name);
        }
        h.setKiosks(names, searching);
        h.self.setMessage(selfText);
    }

    private void closeHome() {
        boolean wasOpen;
        synchronized (this) {
            wasOpen = homeOpen;
            homeOpen = false;
        }
        host.hideOverlay("home");
        if (wasOpen) updateSelfFeed();
    }

    private void wake() {
        host.executeCommand("stopScreensaver", Collections.<String, Object>emptyMap(), (ok, data, error) -> {});
        host.executeCommand("screenOn", Collections.<String, Object>emptyMap(), (ok, data, error) -> {});
    }

    // ---- Helpers ----

    private void publishState(String state) {
        try {
            host.publishTextSensor("call_state", "Video call", state.length() > 255 ? state.substring(0, 255) : state);
        } catch (RuntimeException unavailable) {
            // Entities are optional.
        }
    }

    private String myName() {
        String configured = settings.name.trim();
        if (!configured.isEmpty()) return configured;
        return deviceName.isEmpty() ? "Kiosk" : deviceName;
    }

    /** The Video call reading with no call on: Idle, or what the key still needs. */
    private String idleState() {
        Settings s = settings;
        if (keyProblem(s) == null) return "Idle";
        return s.key.trim().isEmpty() ? "Needs an Intercom key" : "Needs a longer Intercom key";
    }

    /** Why the Intercom key cannot be used, for the status line, or null when it can. */
    private static String keyProblem(Settings s) {
        String key = s.key.trim();
        if (key.isEmpty()) return "Set an Intercom key, the same on every kiosk.";
        if (key.length() < MIN_KEY) {
            return "The Intercom key is too short, so calls are off. Use a long random key of at least " + MIN_KEY
                    + " characters, the same on every kiosk.";
        }
        return null;
    }

    private static String refusal(String reason) {
        switch (reason) {
            case "busy": return "Busy in another call";
            case "dnd": return "Do not disturb";
            case "declined": return "Declined";
            case "no answer": return "No answer";
            case "key": return "Different intercom key";
            case "version": return "Update Video Intercom on both kiosks";
            default: return "Call refused";
        }
    }

    private static Settings parse(Map<String, Object> values) {
        Settings s = new Settings();
        s.key = string(values, "key", "");
        s.name = string(values, "name", "");
        s.port = number(values, "port", 2391);
        s.rtspPort = number(values, "rtspPort", 8080);
        s.rtspPath = string(values, "rtspPath", "/camera");
        s.rtspUser = string(values, "rtspUser", "");
        s.rtspPassword = string(values, "rtspPassword", "");
        s.answerMode = string(values, "answerMode", "Ring");
        s.ringSeconds = Math.min(120, Math.max(10, number(values, "ringSeconds", 30)));
        s.callVolume = number(values, "callVolume", 80);
        s.ringVolume = number(values, "ringVolume", 70);
        s.microphone = string(values, "microphone", "Automatic");
        s.micRate = string(values, "micRate", "Automatic");
        s.muteWakeWord = !Boolean.FALSE.equals(values.get("muteWakeWord"));
        s.selfView = !Boolean.FALSE.equals(values.get("selfView"));
        s.endGesture = string(values, "endGesture", "Double-tap the video");
        s.hangupKey = string(values, "hangupKey", "Off");
        Object peers = values.get("peers");
        if (peers instanceof List) {
            for (Object row : (List<?>) peers) {
                if (!(row instanceof Map)) continue;
                Map<?, ?> map = (Map<?, ?>) row;
                Object address = map.get("address");
                if (!(address instanceof String) || ((String) address).trim().isEmpty()) continue;
                String hostPart = ((String) address).trim();
                int port = s.port;
                int colon = hostPart.lastIndexOf(':');
                if (colon > 0 && hostPart.indexOf(':') == colon) {
                    try {
                        port = Integer.parseInt(hostPart.substring(colon + 1));
                        hostPart = hostPart.substring(0, colon);
                    } catch (NumberFormatException notAPort) {
                        // Keep it whole.
                    }
                }
                Object name = map.get("name");
                String label = name instanceof String && !((String) name).trim().isEmpty() ? ((String) name).trim() : hostPart;
                s.peers.add(new Peer(label, hostPart, port));
            }
        }
        return s;
    }

    private static String string(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    private static int number(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return (int) Double.parseDouble(((String) value).trim().toLowerCase(Locale.ROOT));
            } catch (NumberFormatException ignored) {
                // Fall back.
            }
        }
        return fallback;
    }
}
