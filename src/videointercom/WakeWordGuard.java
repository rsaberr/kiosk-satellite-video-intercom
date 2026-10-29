// SPDX-License-Identifier: Apache-2.0
package videointercom;

import java.util.Collections;
import java.util.Map;

/**
 * Mutes Kiosk Satellite's wake word during a call, so the other kiosk's voice cannot set it off and the two microphone
 * captures do not compete. It only unmutes what it muted itself: a wake word the user had muted stays muted. Whether
 * it muted is remembered across sessions through the host (the plugin keeps it in a hidden setting), because Kiosk
 * Satellite revokes the host before it stops a plugin: a call cut off by a stop, a crash or an update would otherwise
 * leave the wake word muted for good. restore() puts it back at the next start.
 */
final class WakeWordGuard {
    /** The host commands this needs, as a seam for tests. */
    interface Host {
        void command(String name, Map<String, Object> arguments, Result result);
        /** Remembers whether this plugin muted the wake word, in a setting that survives restarts. */
        void remember(boolean mutedByUs);
    }

    interface Result {
        void done(boolean ok, Object data);
    }

    private final Host host;
    private boolean wanted;  // the call wants the wake word quiet
    private boolean mutedByUs;
    private boolean busy;  // a read or a mute command is on its way
    private boolean remembered;  // what the host last kept for the next session

    WakeWordGuard(Host host) {
        this.host = host;
    }

    /** The session started: if a previous one muted the wake word and never got to unmute it, unmute it now. */
    synchronized void restore(boolean rememberedMutedByUs) {
        if (!rememberedMutedByUs) return;
        remembered = true;
        mutedByUs = true;
        settle();
    }

    /**
     * The saved settings changed. A settings form saved during a call can carry an older value than the one remembered
     * since, so the current one is written again when they differ.
     */
    synchronized void saved(boolean rememberedMutedByUs) {
        if (rememberedMutedByUs != remembered) host.remember(remembered);
    }

    /** A call's voice went on (true) or the call ended (false). */
    synchronized void setActive(boolean active) {
        wanted = active;
        settle();
    }

    /** Brings the wake word to what the call wants, one command at a time. */
    private void settle() {
        if (busy) return;
        if (wanted && !mutedByUs) {
            busy = true;
            host.command("getWakeWordState", Collections.<String, Object>emptyMap(), (ok, data) -> {
                Map<?, ?> state = ok && data instanceof Map ? (Map<?, ?>) data : null;
                boolean canMute = state != null && Boolean.TRUE.equals(state.get("available")) && !Boolean.TRUE.equals(state.get("muted"));
                if (!canMute) {
                    finish(false);  // no wake word, or the user muted it: leave it alone
                    return;
                }
                // Remembered before the mute is sent, so a stop or a crash right after it still leaves a way back.
                remember(true);
                host.command("setWakeWordMuted", Collections.<String, Object>singletonMap("muted", Boolean.TRUE), (muted, ignored) -> {
                    if (!muted) remember(false);
                    finish(muted);
                });
            });
        } else if (!wanted && mutedByUs) {
            busy = true;
            host.command("setWakeWordMuted", Collections.<String, Object>singletonMap("muted", Boolean.FALSE), (unmuted, ignored) -> {
                synchronized (WakeWordGuard.this) {
                    busy = false;
                    mutedByUs = !unmuted;
                    if (unmuted) remember(false);
                    // A failed unmute is not retried in a loop; the remembered flag brings it back at the next start.
                    if (unmuted) settle();
                }
            });
        }
    }

    private synchronized void remember(boolean mutedByUs) {
        remembered = mutedByUs;
        host.remember(mutedByUs);
    }

    private synchronized void finish(boolean muted) {
        busy = false;
        mutedByUs = muted;
        // Leave it as it is when the read refused (no wake word, or muted by the user) until the call ends.
        if (muted || !wanted) settle();
    }
}
