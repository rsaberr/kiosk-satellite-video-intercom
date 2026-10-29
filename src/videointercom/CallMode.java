// SPDX-License-Identifier: Apache-2.0
package videointercom;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/**
 * Android's call mode for the length of a call. Most devices only cancel the echo of their own speaker while the
 * audio mode is MODE_IN_COMMUNICATION (an Amazon Fire tablet loops the other side's voice back otherwise); the RK3576
 * panel suppresses it without. Call mode would send call audio to a phone's earpiece, so the loudspeaker is chosen
 * explicitly. Whatever was set before is put back afterwards, but only what still is this plugin's: a mode or a
 * route that someone else changed during the call stays, and while Kiosk Satellite's own intercom has something on,
 * the change waits until it is done.
 */
final class CallMode {
    private final AudioManager audio;
    private int previousMode = -1;
    private boolean previousSpeakerphone;
    /** The loudspeaker this set as the communication device, on Android 12 and newer. */
    private AudioDeviceInfo ourDevice;
    /** The call ended while the intercom had something on: leaving waits for finishLeave(). */
    private boolean waiting;

    CallMode(Context context) {
        audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    /** Enters call mode on the loudspeaker, once per call; returns what happened, for the log. */
    @SuppressWarnings("deprecation")
    synchronized String enter() {
        waiting = false;  // still in the mode from a call that waited to leave it: the new call keeps it
        if (previousMode >= 0) return null;
        try {
            previousMode = audio.getMode();
            audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
            String route = "no loudspeaker";
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        boolean set = audio.setCommunicationDevice(device);
                        if (set) ourDevice = device;
                        route = set ? "loudspeaker" : "loudspeaker refused";
                        break;
                    }
                }
            } else {
                previousSpeakerphone = audio.isSpeakerphoneOn();
                audio.setSpeakerphoneOn(true);
                route = "speakerphone";
            }
            return "call mode " + (audio.getMode() == AudioManager.MODE_IN_COMMUNICATION ? "on" : "refused") + ", " + route;
        } catch (RuntimeException refused) {
            return "call mode: " + refused;
        }
    }

    /**
     * Leaves call mode and puts back the mode and route from before the call. With intercomBusy true, Kiosk
     * Satellite's own intercom has a call or an announcement on that started under this mode, so nothing changes
     * until finishLeave().
     */
    synchronized void leave(boolean intercomBusy) {
        if (previousMode < 0) return;
        if (intercomBusy) {
            waiting = true;
            return;
        }
        restore();
    }

    /** The intercom is done: finishes a leave that waited for it. Does nothing if none waits. */
    synchronized void finishLeave() {
        if (waiting) restore();
    }

    @SuppressWarnings("deprecation")
    private void restore() {
        try {
            // Another app that switched the mode during the call owns it now, and its route with it.
            boolean ours = audio.getMode() == AudioManager.MODE_IN_COMMUNICATION;
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo now = audio.getCommunicationDevice();
                if (ourDevice != null && now != null && now.getId() == ourDevice.getId()) audio.clearCommunicationDevice();
            } else if (ours) {
                audio.setSpeakerphoneOn(previousSpeakerphone);
            }
            if (ours) audio.setMode(previousMode);
        } catch (RuntimeException ignored) {
            // Nothing more to do.
        }
        previousMode = -1;
        ourDevice = null;
        waiting = false;
    }
}
