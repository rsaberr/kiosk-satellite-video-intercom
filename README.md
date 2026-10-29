# Video Intercom for Kiosk Satellite

<p align="center"><img src="assets/icon.png" alt="Video Intercom icon" width="128"></p>

Video calls between Kiosk Satellite kiosks: the other kiosk's live camera, full screen, with two-way hands-free voice.
The person being called sees the caller while it rings; the caller sees the other side once it answers.

Kiosks talk directly on the local network and find each other with a UDP broadcast. Each kiosk listens for calls on
port 2391 (TCP, and UDP for finding the others). The picture is the other kiosk's own camera stream, which Kiosk Satellite already serves over RTSP; the voice travels on the plugin's own link
as 16 kHz audio, played as call audio so Android's echo canceller can work.

This is separate from Kiosk Satellite's built-in intercom, whose call screen covers plugin windows.

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

## Setup, on every kiosk

1. **Camera › RTSP & ONVIF Streaming**: turn on **Enable camera streaming**. Turning off **Include microphone audio**
   is recommended, so the stream does not compete with the call for the microphone.
2. Install the plugin (see Installation) and enable it.
3. In the plugin's settings:
   - **Intercom key**: any passphrase, the same on every kiosk.
   - Nothing else: kiosks with the same key find each other on the network. **Extra kiosks** is only for one that is
     not found, such as on another subnet.
4. Call from **Open video call**: add it to the kiosk drawer, a gesture, or expose it to Home Assistant from the
   plugin page. It opens the Video call screen: a live preview of this kiosk's own camera and a button for every kiosk
   found. During a call your own camera shows small in the corner.

## Where your data goes

Everything stays on your local network. The plugin contacts no cloud service, no Home Assistant and no other server.

- **Finding kiosks:** a UDP broadcast on the call port (2391) carries this kiosk's name and a fingerprint of the
  intercom key (an HMAC, never the key itself). Kiosks answer with their name.
- **Calls:** a direct TCP connection to the other kiosk on port 2391. Both kiosks prove they hold the same key with an
  HMAC-SHA256 challenge before anything else happens. The voice (16 kHz audio) then travels on that connection. It is
  not encrypted, like Kiosk Satellite's own intercom, so use it on a network you trust.
- **Video:** each kiosk reads the other kiosk's camera stream, and its own for the preview, from Kiosk Satellite's
  RTSP server on your network.
- **Home Assistant:** only the **Video call** state sensor, through Kiosk Satellite's ESPHome connection, and the
  **Open video call** button if you expose it.

The microphone is only open while a call is connected: after Answer, or 3 seconds into the ring when Answer mode is
Answer automatically. The person being called sees the caller's camera while it
rings; the caller sees the other side only once it answers.

## Home Assistant

**Open video call** can be exposed as an ESPHome button from the plugin page. The **Video call** text sensor shows
Idle, Calling …, Ringing from … or In call with …, and the **Incoming video call** gesture trigger runs on every
incoming call.

Answering and ending happen on the call screen itself, with its buttons, a double tap on the video or a hardware
button. Kiosk Satellite's own gestures do not reach through the call screen.

## Settings

| Setting | What it does | Default |
|---|---|---|
| Intercom key | Shared passphrase. Kiosks with the same key find each other, and prove it to each other without sending it | |
| This kiosk's name | Shown on the other kiosk | the device name |
| Extra kiosks | Optional: kiosks that are not found by themselves, by name and IP address (`:port` optional) | |
| Answer mode | Ring, Answer automatically (after 3 s), or Do not disturb | Ring |
| Ring for | How long an incoming call rings before it counts as missed | 30 s |
| End call with | Buttons only, Double-tap the video, or Long-press the video. Kiosk Satellite's own gestures do not reach through the call screen | Double-tap the video |
| Hang up with button | Off, Volume up or Volume down | Off |
| Show my camera during calls | Your own camera small in the corner of the call | on |
| Ring volume, Call volume | Loudness of the ring and of the other kiosk's voice | 70 %, 80 % |
| Microphone | Android microphone source. Automatic tries Voice communication first | Automatic |
| Pause the wake word during calls | Mutes the wake word while a call is live | on |
| RTSP port, path, username, password | How to reach the other kiosks' camera streams. 8080 when streaming uses ONVIF, 8554 for RTSP; the other is tried if one is refused | 8080, `/camera` |
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
