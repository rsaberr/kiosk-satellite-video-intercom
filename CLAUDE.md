# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Kiosk Satellite plugin (Android, plain Java, no Gradle) for video calls between kiosks on a LAN. The picture is the
other kiosk's own RTSP camera stream; the voice and signalling travel on the plugin's own TCP link (port 2391, which
is also the UDP discovery port). README.md is the user-facing spec for settings and behaviour; keep it in sync with
`kiosk-satellite-plugin.json` when settings change.

## Commands

Needs JDK 17+ (respects `JAVA_HOME`). Build also needs an Android SDK with a platform and build-tools.

```sh
python3 tools/test.py                                   # tests, no device needed
ANDROID_HOME=~/android-sdk python3 tools/build.py       # -> dist/video-intercom-<version>.zip (+ .sha256, manifest)
python3 tools/build.py --android-platform 35 --version 1.2.3
```

- There is one test program, `tests/videointercom/CallLinkTest.java`, a plain `main` with `check(...)` (no JUnit).
  `test.py` compiles it together with **only** `CallLink.java`, `KeyRing.java` and `Discovery.java` from
  `src/videointercom` against the plain JDK, so those three must stay free of Android and SDK imports. `test.py` also asserts the manifest's shape (unique setting keys,
  ≤50 settings, ≤20 commands, icon exists in `assets/`).
- `build.py` compiles `sdk/src` and `src` with `--release 8`, dexes with the newest `d8` at `minAndroidSdk`, and fails
  if the ZIP exceeds 4 MB. Everything in `assets/` is packaged (strict filename pattern). Both scripts compile
  sources as UTF-8 (strings use characters such as "…"), and `build.py` also finds `d8.bat` on Windows.
- Releases: pushing a `v*`/`V*` tag runs `.github/workflows/build.yml`, which tests, builds with the tag as version,
  and uploads to the GitHub release as github-actions[bot] (Kiosk Satellite checks that before installing). The
  `version` in the manifest is only the local default.

## Architecture

- `sdk/` holds the Kiosk Satellite plugin SDK interfaces (`KioskPlugin`, `PluginHost`, `OverlayFactory`, ...). They
  are compile-time only, not packaged, and copied from upstream; don't edit them to make code compile.
- `VideoIntercomPlugin` (manifest `entryClass`) is the hub and owns the call state machine
  (`IDLE → OUTGOING/INCOMING → IN_CALL → ENDING`). State is guarded by `synchronized (this)`; every call gets an
  incrementing `callId`, and async callbacks/timers capture it and bail out if `callId` changed. Follow this pattern
  for any new async step.
- Threads: SDK callbacks run serially on a host worker thread and must return quickly; overlay views run on the
  Android main thread; network work goes through `run(...)` on the single `vi-work` executor; timeouts/keepalives on
  `vi-timers`. Errors are reported with `trace(...)` (host log), never thrown into Kiosk Satellite. Incoming
  connections are handed from `vi-server` to a bounded `vi-incoming` handshake pool (4 at once, 2 per address); a
  `vi-timers` watchdog closes any connection not rung or refused within 5 s, and an address that fails 5 handshakes
  within a minute is turned away for a minute (`admit`/`finished`). A key under 16 characters (`keyProblem`) turns
  listening, discovery and calling off; the status line and the `call_state` reading say why.
- `KeyRing`: the passphrase as HMAC key material, stretched with PBKDF2-HMAC-SHA256 (100,000 iterations) over a salt
  made fresh each session. It caches derived keys by peer salt (proved keys apart from merely derived ones, both LRU)
  and rate-limits derivations for unasked salts from discovery. Deriving is slow, so never on the host worker or main
  thread.
- `CallLink`: one TCP connection per call. Frames are type byte + 16-bit length + payload. The callee opens with
  `CHALLENGE` = nonce + its salt; the caller answers `HELLO` with its nonce, HMAC proof and name; the callee's
  `RINGING` proves the key back. HMACs are labelled `ks-video-intercom-2:<role>`; a 16-byte `CHALLENGE` means an old
  (version 1) peer. The key never crosses the wire. Audio is unencrypted 16 kHz PCM. `cleanName` strips control
  characters and caps names at 80 characters.
- `Discovery`: a broadcast `VI2|Q|nonce` question; kiosks answer only the asker with `VI2|A|nonce|salt|hmac|name`,
  the HMAC over that nonce and name with their derived key. Answers count only for a nonce sent in the last 15 s.
  Answers and asks-back are rate-limited, and the list keeps at most 32 kiosks, forgetting any silent for 5 minutes.
- Audio path: `MicCapture` → `TalkChannel` (implemented by `LinkTalk` inside the plugin, sends `AUDIO` frames) →
  network → `PcmPlayer` (voice-call stream with a small jitter buffer). `CallMode` switches Android to
  `MODE_IN_COMMUNICATION` on the loudspeaker so the echo canceller works; `leave` waits while KS's intercom has
  something on (`finishLeave`) and only restores the mode and route it still owns. `WakeWordGuard` mutes the KS wake
  word during calls and only unmutes what it muted; it persists that flag in the hidden `wakeWordRestore` setting via
  `host.updateSettings` and restores it at start. KS revokes the host before `stop()`, so `stop()` must not rely on
  host calls.
- KS's own intercom: the plugin follows it (`intercom.state` event plus `getIntercomState`). Any of calling, ringing,
  in_call, broadcasting, listening makes this kiosk busy (incoming refused with `busy`, outgoing not dialled);
  in_call or broadcasting during a call ends the call (`yieldToIntercom`).
- Video path: `VideoFeed` = `media/StreamHub` (RTSP client: `RtspSession`, `Sdp`, H.264/AAC depacketizers) +
  `VideoRenderer` (low-latency MediaCodec onto a `Surface`). `FrameHub` caches frames since the last keyframe so a
  late-attaching surface gets a picture immediately. `VideoFeed` falls back between ports 8080 (ONVIF) and 8554 (RTSP).
  The surface can be swapped while the stream keeps running (preview screen → call screen).
- UI: two overlays, `HomeView` (own-camera preview + a button per found kiosk, opened by the `openMenu` command) and
  `CallView` (full-screen call). Both are plain Android views styled from `KsTheme`.
- Code under `media/`, `VideoRenderer`, `MicCapture`, `CallMode`, `WakeWordGuard`, `Downsampler`, `TalkChannel` is
  adapted from the Reolink Doorbell Intercom plugin (Apache-2.0, see NOTICE); keep SPDX headers and NOTICE accurate.

## Conventions

- Every source file starts with `// SPDX-License-Identifier: Apache-2.0`.
- Java 8 language level only (no `var`, records, switch expressions); `minAndroidSdk` 26, guard newer APIs with
  `Build.VERSION.SDK_INT`.
- Comments and user-facing strings use plain, full-sentence prose describing behaviour (see existing Javadoc);
  manifest descriptions and README text follow the same style.
