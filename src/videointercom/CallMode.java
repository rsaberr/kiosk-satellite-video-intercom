// SPDX-License-Identifier: Apache-2.0
package videointercom;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

/**
 * Android's call mode for the length of a call. Most devices only cancel the echo of their own speaker while the
 * audio mode is MODE_IN_COMMUNICATION (an Amazon Fire tablet loops the door audio back otherwise); the RK3576 panel
 * suppresses it without. Call mode would send call audio to a phone's earpiece, so the loudspeaker is chosen
 * explicitly. Whatever was set before is put back afterwards.
 */
final class CallMode {
    private final AudioManager audio;
    private int previousMode = -1;
    private boolean previousSpeakerphone;

    CallMode(Context context) {
        audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    /** Enters call mode on the loudspeaker, once per call; returns what happened, for the log. */
    @SuppressWarnings("deprecation")
    synchronized String enter() {
        if (previousMode >= 0) return null;
        try {
            previousMode = audio.getMode();
            audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
            String route = "no loudspeaker";
            if (Build.VERSION.SDK_INT >= 31) {
                for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        route = audio.setCommunicationDevice(device) ? "loudspeaker" : "loudspeaker refused";
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

    /** Leaves call mode and puts back the mode and route from before the call. */
    @SuppressWarnings("deprecation")
    synchronized void leave() {
        if (previousMode < 0) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.clearCommunicationDevice();
            } else {
                audio.setSpeakerphoneOn(previousSpeakerphone);
            }
            audio.setMode(previousMode);
        } catch (RuntimeException ignored) {
            // Nothing more to do.
        }
        previousMode = -1;
    }
}
