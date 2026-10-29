// SPDX-License-Identifier: Apache-2.0
package videointercom;

/** Where the microphone's audio goes: the call link to the other kiosk. */
public interface TalkChannel {
    void start();

    /** Stops sending and closes the session; safe from any thread except the main one (it may wait briefly). */
    void stop();

    /** Microphone audio at rate(). The oldest audio is dropped rather than letting the delay grow. */
    void write(short[] samples, int count);

    /** The microphone stopped (muted or ended): forget what was queued and go back to silence. */
    void clear();

    /** The sample rate write() takes. */
    int rate();
}
