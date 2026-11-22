# SmartGimbal

Face tracking and offline voice control for the **FeiyuTech VLOG pocket** gimbal, as a native
Android app. The phone in the clamp films, detects your face, steers the gimbal over Bluetooth to
keep you in frame, and listens for spoken commands such as "Aufnahme starten" or "Käse", without a
cloud connection.

> **Unofficial project.** Not affiliated with or endorsed by FeiyuTech. The Bluetooth protocol is
> reverse-engineered and incomplete; use at your own risk.

## Features

- **Face tracking.** ML Kit face detection steers pan and tilt through a PD controller that was tuned against a simulation of the real gimbal.
- **Offline voice control (German).** Vosk with a fixed command grammar handles recording, photo self-timer, zoom, tracking and home.
- **Recording.** 4K/30 video with sound while tracking runs (rear camera; the front camera is limited to 1080p).
- **Photo.** A spoken "Käse" starts a 3 s self-timer.
- **Zoom.** 0.67× (ultrawide), 1×, 2× and 4× by voice or button. The tracker adapts its field of view.
- **Gimbal control.** One-tap connect, manual D-pad, home, live telemetry (pan, tilt, roll).
- **Debug tools.** Raw frame sender with automatic CRC, log of all BLE traffic, tuning sliders for tracker and voice.

## Requirements

- **Gimbal:** FeiyuTech VLOG pocket. It advertises as `FY_PocketV_xx`.
- **Phone:** Android 13 or newer, 64-bit ARM. Developed and tested on a Google Pixel 7 with Android 17.
  - Tracker tuning and camera behaviour are device-specific. Other phones will work but may need retuning in the debug screen.
- **Build:** JDK 17 and the Android SDK (compile SDK 36).

## Build and install

```bash
git clone <this repository> && cd <repository folder>
scripts/fetch-vosk-model.sh   # downloads the German Vosk model (46.5 MB), verifies its SHA-256
./gradlew :app:installDebug   # phone connected with USB debugging
```

The Vosk model is bundled into the APK but not stored in git; the build stops with a hint if it
is missing. The debug APK is about 110 MB, most of it the speech model.

Run the unit tests (protocol, tracker simulation, voice parser):

```bash
./gradlew :app:testDebugUnitTest
```

## Usage

1. Switch on the gimbal and open the app. Grant camera, Bluetooth and microphone access.
2. Tap **Gimbal verbinden**. The app connects to the first `FY_PocketV_*` it finds.
3. Tap **Tracking** or say "Tracking an". The gimbal keeps the green-framed face centred.
4. Record with the red button or say "Aufnahme starten". Files go to `Movies/SmartGimbal` and `Pictures/SmartGimbal`.

The **?** button on the camera screen opens a help screen with all voice commands and tips.

### Voice commands

The app listens only while the camera screen is open and voice is enabled. Recognised commands
vibrate and show a short note.

| Say | Effect |
| --- | --- |
| „Aufnahme starten" | start video recording |
| „Stopp" / „Aufnahme stopp" / „Aufnahme stoppen" / „Stoppen" / „Abbrechen" | stop recording or cancel the photo self-timer |
| „Käse" (a spoken "Cheese" works too) | photo after a 3 s countdown (only when not recording) |
| „Tracking an" / „Tracking aus" | face tracking on / off |
| „Kamera zurück" | gimbal back to its centre position |
| „Weitwinkel an" / „Weitwinkel aus" | ultrawide lens (0.67×) / back to 1× |
| „Zoom zwei" / „Zoom vier" | 2× / 4× zoom |
| „Zoom aus" / „Zoom normal" | back to 1× |

Commands are German because the bundled model is German. Some English words are not in the
model's vocabulary: "gimbal" is missing, hence "Kamera zurück", and "cheese" is missing but gets
mapped to "Käse".

## How it works

- **Bluetooth.** The gimbal speaks a framed protocol over the Nordic UART service. A 20 Hz send loop transmits speed commands, and the D-pad always wins over the tracker. Telemetry arrives at about 2.4 Hz. Details in [docs/protocol.md](docs/protocol.md).
- **Camera.**
  - Problem: CameraX binds preview and video only, because preview + YUV analysis + 4K video is not a supported stream combination on the Pixel 7.
  - Solution: face detection reads quarter-size copies of the visible preview (TextureView, about 3 ms per copy), which gives about 23 analysed frames per second.
  - Photos: the controller switches to image capture during the self-timer and back afterwards.
- **Tracking.**
  - The face position becomes an angular error from the camera's real field of view (including the 16:9 crop and the zoom).
  - A PD controller per axis turns it into joystick speed, with a deadzone with hysteresis, ramp limits, a soft tilt end stop and learned end stops.
  - The gains come from closed-loop simulations of the measured gimbal response (about 0.22°/s per unit, its own smoothing τ ≈ 0.25 s).
- **Voice.**
  - Vosk runs on the app's own `AudioRecord` next to the CameraX audio track. On the Pixel 7 both receive audio at the same time.
  - A fixed grammar keeps recognition fast and avoids random triggers.
  - Only whole commands with sufficient confidence are executed, and only when they fit the current state (e.g. "Stopp" needs a recording or a running self-timer).

Design notes and test logs from development (German): [project description](project-description.md),
[phase 2 plan](docs/phase-2-plan.md), [phase 3 plan](docs/phase-3-plan.md).

## Project structure

```
app/src/main/java/dev/smartgimbal/
  protocol/   frames, CRC, telemetry, stream reassembly
  ble/        BLE client (scan, connect, notifications, writes)
  gimbal/     session with send loop and command arbitration
  camera/     video recorder, photo self-timer, preview frame grabber, zoom steps
  tracking/   face detector interface (ML Kit), target selection, FOV, PD controller
  voice/      microphone, Vosk engine, command parser, voice control
  ui/         Compose screens: camera, help, debug
scripts/      fetch-vosk-model.sh
docs/         protocol notes and design plans
```

Logs from a running app (handy for tuning):

```bash
adb logcat -s SmartGimbal
```

## Known limitations

- **Voice:** German only; the small model works best within a few metres. Several commands in one breath are ignored, so pause between them.
- **Camera:** The front camera records at most 1080p and has no ultrawide.
- **Protocol:** Gimbal modes and holder rotation are not reverse-engineered yet.
- **Device-specific:** Tuning is device-specific (tested on one Pixel 7 and one VLOG pocket).

## Credits and third-party licenses

- [Shalunizm/FeiyuTech-Pocket-Vlog-Protocol](https://github.com/Shalunizm/FeiyuTech-Pocket-Vlog-Protocol): the reverse-engineered basics of the BLE protocol.
- [Vosk](https://alphacephei.com/vosk/) speech recognition and the `vosk-model-small-de-0.15` model, Apache License 2.0.
- [ML Kit face detection](https://developers.google.com/ml-kit/vision/face-detection), under the [ML Kit terms](https://developers.google.com/ml-kit/terms).
- [CameraX](https://developer.android.com/jetpack/androidx/releases/camera), [Jetpack Compose](https://developer.android.com/jetpack/compose), Apache License 2.0.

## Contributing

Issues and pull requests are welcome, especially:
- protocol findings (modes, holder rotation)
- tests on other phones
- voice models for other languages

Please keep `./gradlew :app:testDebugUnitTest` green.

## License

[MIT](LICENSE) © 2026 itdeiti. The bundled speech model and the libraries keep their own licenses (see above).
