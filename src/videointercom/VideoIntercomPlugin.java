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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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

    // Finding the other kiosks.
    private Discovery discovery;
    private String discoveryKey;
    private int discoveryPort;

    // Listening for calls.
    private ServerSocket server;
    private Thread acceptor;
    private int serverPort;

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
                host.executeCommand(name, arguments, (ok, data, error) -> result.done(ok, data));
            }

            @Override public void remember(boolean mutedByUs) {
                // Not kept across restarts: a call cut off by a crash can leave the wake word muted until unmuted.
            }
        });
        settings = parse(values);
        publishState("Idle");
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
        timers.scheduleAtFixedRate(() -> {
            Discovery d = discovery;
            if (d != null) d.probe();
        }, 60, 60, TimeUnit.SECONDS);
    }

    @Override public void configure(Map<String, Object> values) {
        settings = parse(values);
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

    @Override public void stop() {
        end(null, "stopped", 0);
        closeServer();
        synchronized (this) {
            if (discovery != null) discovery.stop();
            discovery = null;
        }
        closeHome();
        host.hideOverlay("call");
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

    /** (Re)starts discovery when the key or port changed. */
    private synchronized void discover() {
        Settings s = settings;
        String key = s.key.trim();
        if (discovery != null && key.equals(discoveryKey) && s.port == discoveryPort) return;
        if (discovery != null) discovery.stop();
        discovery = null;
        discoveryKey = key;
        discoveryPort = s.port;
        if (key.isEmpty()) return;
        discovery = new Discovery(s.port, CallLink.keyBytes(key), this::myName);
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

    private synchronized void listen() {
        Settings s = settings;
        if (s.key.trim().isEmpty()) {
            closeServer();
            host.status("Set an Intercom key, the same on every kiosk", true);
            return;
        }
        if (server != null && serverPort == s.port) {
            host.status("Ready on port " + s.port, false);
            return;
        }
        closeServer();
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(s.port));
            server = socket;
            serverPort = s.port;
            Thread thread = new Thread(() -> acceptLoop(socket), "vi-server");
            thread.setDaemon(true);
            acceptor = thread;
            thread.start();
            host.status("Ready on port " + s.port, false);
        } catch (IOException error) {
            host.status("Cannot listen on port " + s.port + ": " + error.getMessage(), true);
        }
    }

    private synchronized void closeServer() {
        if (server != null) {
            try { server.close(); } catch (IOException ignored) {}
        }
        server = null;
        acceptor = null;
        serverPort = 0;
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                Thread thread = new Thread(() -> incoming(client), "vi-incoming");
                thread.setDaemon(true);
                thread.start();
            } catch (Throwable closed) {
                return;
            }
        }
    }

    private void incoming(Socket socket) {
        try {
            takeCall(socket);
        } catch (Throwable error) {
            trace("incoming call failed: " + error);
        }
    }

    private void takeCall(Socket socket) {
        CallLink candidate;
        try {
            candidate = CallLink.answer(socket, CallLink.keyBytes(settings.key));
        } catch (IOException refused) {
            host.log("refused a call: " + refused.getMessage());
            return;
        }
        Settings s = settings;
        int id;
        synchronized (this) {
            if (mode != Mode.IDLE) {
                candidate.refuse("busy");
                return;
            }
            if ("Do not disturb".equals(s.answerMode)) {
                candidate.refuse("dnd");
                return;
            }
            try {
                candidate.ring(myName());
            } catch (IOException gone) {
                candidate.close();
                return;
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
    }

    // ---- Placing and taking calls ----

    private void call(Peer peer) {
        Settings s = settings;
        if (s.key.trim().isEmpty()) {
            host.status("Set an Intercom key, the same on every kiosk", true);
            return;
        }
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
            dialled = CallLink.dial(peer.host, peer.port, CallLink.keyBytes(s.key), myName());
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
        if (callMode != null) callMode.leave();
        if (inCall) wakeWord.setActive(false);
        publishState("Idle");
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
        callMode.enter();
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

    private static String refusal(String reason) {
        switch (reason) {
            case "busy": return "Busy in another call";
            case "dnd": return "Do not disturb";
            case "declined": return "Declined";
            case "no answer": return "No answer";
            case "key": return "Different intercom key";
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
        s.ringSeconds = Math.max(5, number(values, "ringSeconds", 30));
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
