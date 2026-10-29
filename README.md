# Video Intercom for Kiosk Satellite

<p align="center"><img src="assets/icon.png" alt="Video Intercom icon" width="128"></p>

Video calls between Kiosk Satellite kiosks: the other kiosk's live camera, full screen, with two-way hands-free voice.
The person being called sees the caller while it rings; the caller sees the other side once it answers.

Kiosks talk directly on the local network and find each other with a UDP broadcast. Each kiosk listens for calls on
port 2391 (TCP, and UDP for finding the others). The picture is the other kiosk's own camera stream, which Kiosk Satellite already serves over RTSP; the voice travels on the plugin's own link
as 16 kHz audio, played as call audio so Android's echo canceller can work.

This is separate from Kiosk Satellite's built-in Intercom, whose call screen covers plugin windows, and the two take
turns with the microphone and speaker. While the Intercom is calling, ringing, in a call, or sending or playing an
announcement, this kiosk counts as busy: incoming video calls are refused, so the caller sees "Busy in another call",
and a video call from this kiosk shows "Busy in an intercom call". If an Intercom call or announcement from this kiosk
starts during a video call, the video call ends with "Ended for an intercom call". After a call, Android's audio mode
is put back only if nothing else changed it in the meantime, and only once the Intercom is done.

## Installation

### Community Plugins (Recommended)

Video Intercom is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=rsaberr/kiosk-satellite-video-intercom)

Or search for `Video Intercom` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/rsaberr/kiosk-satellite-video-intercom` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

Install it on every kiosk that should make or take video calls.

### Updating: all kiosks together

This version changed how kiosks find each other and prove the intercom key, so update every kiosk at the same time.
Kiosks on different versions cannot find or call each other: a call from an updated kiosk to one that is not shows
"Update Video Intercom on both kiosks". This version also needs an Intercom key of at least 16 characters: set a new
long key on every kiosk if yours is shorter.

## Setup, on every kiosk

1. **Camera › RTSP & ONVIF Streaming**: turn on **Enable camera streaming**. Turning off **Include microphone audio**
   is recommended, so the stream does not compete with the call for the microphone. Setting an RTSP username and
   password there is recommended too (see Security).
2. Install the plugin (see Installation) and enable it.
3. In the plugin's settings:
   - **Intercom key**: a long random key of at least 16 characters, the same on every kiosk. A password manager can
     make one. With a shorter key, calls are off: the kiosk takes no calls, makes none and is not found, and the
     plugin page says why.
   - **RTSP username** and **RTSP password**: the camera streaming login from step 1, if you set one.
   - Nothing else: kiosks with the same key find each other on the network. **Extra kiosks** is only for one that is
     not found, such as on another subnet.
4. Call from **Open video call**: add it to the kiosk drawer, a gesture, or expose it to Home Assistant from the
   plugin page. It opens the Video call screen: a live preview of this kiosk's own camera and a button for every kiosk
   found. During a call your own camera shows small in the corner.

## Where your data goes

Everything stays on your local network. The plugin contacts no cloud service, no Home Assistant and no other server.

- **Finding kiosks:** a kiosk asks who is there with a UDP broadcast on the call port (2391) that carries only a fresh
  random question. Kiosks with the same key answer the asking kiosk alone with their name and a proof made with the
  key for that question (an HMAC, never the key itself).
- **Calls:** a direct TCP connection to the other kiosk on port 2391. Both kiosks prove they hold the same key with an
  HMAC-SHA256 challenge before anything else happens. The voice (16 kHz audio) then travels on that connection. It is
  not encrypted, like Kiosk Satellite's own intercom, so use it on a network you trust.
- **Video:** each kiosk reads the other kiosk's camera stream, and its own for the preview, from Kiosk Satellite's
  RTSP server on your network, with the RTSP login from the plugin's settings if one is set.
- **Home Assistant:** only the **Video call** state sensor, through Kiosk Satellite's ESPHome connection, and the
  **Open video call** button if you expose it.

The microphone is only open while a call is connected: after Answer, or 3 seconds into the ring when Answer mode is
Answer automatically. The person being called sees the caller's camera while it
rings; the caller sees the other side only once it answers.

## Security

- **Camera streams:** the plugin needs Kiosk Satellite's RTSP camera stream, which has no login by default. Then
  anyone on the local network can watch every kiosk's camera, whether or not this plugin is installed. Set an RTSP
  username and password in Kiosk Satellite's camera streaming settings, and the same login in this plugin's
  **RTSP username** and **RTSP password**, on every kiosk.
- **Intercom key:** the key never crosses the network. Each kiosk stretches it with PBKDF2-HMAC-SHA256 (100,000
  iterations) over a random salt it makes each time the plugin starts. That makes guessing the key from recorded
  traffic slow, but it cannot stop it for a weak key, so use a long random key of at least 16 characters, such as one
  made by a password manager. A shorter key is refused: calls stay off until the key is long enough. Because of the
  stretching, the first call to a kiosk after either one restarts can take a moment longer.
- **Guessing over the network:** a kiosk that connects has 5 seconds to finish proving the key. At most 4 such
  connections are checked at once, and at most 2 from one address. An address that fails to prove the key 5 times
  within a minute is turned away for a minute.
- **Finding kiosks:** each question is new and random, and an answer counts only for the question it proves, so a
  recorded answer cannot be replayed to add a fake kiosk. Answers are limited to a few a second. The list holds at
  most 32 kiosks and forgets one not heard from for 5 minutes. Names from other kiosks are shown without control
  characters and cut to 80 characters.
- **Answer automatically:** with this Answer mode, anyone with the key gets this kiosk's audio and video without
  anyone touching the screen. Use it only where that is wanted.
- **Voice:** after the handshake, the voice travels unencrypted, like Kiosk Satellite's own intercom. Anyone who can
  watch the traffic on your network can listen to it, so use the plugin on a network you trust.
- **Wake word:** with **Pause the wake word during calls** on, if Kiosk Satellite stops or crashes during a call, the
  plugin unmutes the wake word at its next start. It only ever unmutes a mute it made itself.

## Home Assistant

**Open video call** can be exposed as an ESPHome button from the plugin page. The **Video call** text sensor shows
Idle, Calling …, Ringing from … or In call with …, and the **Incoming video call** gesture trigger runs on every
incoming call.

Answering and ending happen on the call screen itself, with its buttons, a double tap on the video or a hardware
button. Kiosk Satellite's own gestures do not reach through the call screen.

## Settings

| Setting | What it does | Default |
|---|---|---|
| Intercom key | Shared key, the same on every kiosk: a long random one of at least 16 characters is required. Kiosks with the same key find each other, and prove it to each other without sending it | |
| This kiosk's name | Shown on the other kiosk | the device name |
| Extra kiosks | Optional: kiosks that are not found by themselves, by name and IP address (`:port` optional) | |
| Answer mode | Ring, Answer automatically (after 3 s), or Do not disturb. Answer automatically gives anyone with the key this kiosk's audio and video without anyone touching the screen | Ring |
| Ring for | How long an incoming call rings before it counts as missed, from 10 to 120 seconds | 30 s |
| End call with | Buttons only, Double-tap the video, or Long-press the video. Kiosk Satellite's own gestures do not reach through the call screen | Double-tap the video |
| Hang up with button | Off, Volume up or Volume down | Off |
| Show my camera during calls | Your own camera small in the corner of the call | on |
| Ring volume, Call volume | Loudness of the ring and of the other kiosk's voice | 70 %, 80 % |
| Microphone | Android microphone source. Automatic tries Voice communication first | Automatic |
| Pause the wake word during calls | Mutes the wake word while a call is live, and unmutes it at the next start if a call was cut off. A wake word you muted yourself stays muted | on |
| RTSP port, path, username, password | How to reach the other kiosks' camera streams. 8080 when streaming uses ONVIF, 8554 for RTSP; the other is tried if one is refused. Set the username and password to the camera streaming login, the same on every kiosk | 8080, `/camera` |
| Call port | Where this kiosk listens for calls | 2391 |

## Build

Needs a JDK 17+ and an Android SDK with a platform and build-tools.

```sh
python3 tools/test.py
ANDROID_HOME=~/android-sdk python3 tools/build.py
```

Releases are built by GitHub Actions: push a tag such as `v1.0.1` and the Release workflow tests, builds and publishes
the release with the manifest, the ZIP and its checksum.

## License

[Apache-2.0](LICENSE). Includes code adapted from
[Reolink Doorbell Intercom](https://github.com/patrick11994/ks-reolink-doorbell-intercom) by patrick11994 (Apache-2.0);
see NOTICE.
