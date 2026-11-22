# SmartGimbal

Android-App (Kotlin, Pixel 7) zur Steuerung des FeiyuTech VLOG pocket per BLE. Konzept: [project-description.md](project-description.md).

Stand: **Phase 1, BLE-Test-App.** Scan nach `FY_PocketV_*`, Verbindung über GATT, Notifications auf RX, Pfeiltasten (senden nur solange gedrückt, mit 20 Hz), Home, Stärke-Regler, Roh-Frame-Sender und ein Log mit TX/RX-Hexdumps.

## Build & Installieren

Voraussetzung: JDK 17 und Android SDK (`ANDROID_HOME` gesetzt oder `local.properties` mit `sdk.dir`).

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:installDebug
```

`installDebug` braucht ein per USB-Debugging verbundenes Pixel 7 (`adb devices`).

## Aufbau

| Datei | Zweck |
| --- | --- |
| `protocol/FeiyuProtocol.kt` | Frames bauen (`A5 5A` + Body + CRC-16/XMODEM LE), Joystick `0x0E`, Home `0x33` |
| `ble/GimbalBleClient.kt` | Scan, Connect, Service-Dump, CCCD, serialisierte Writes |
| `GimbalViewModel.kt` | Send-Loop (50 ms), Stärke, Roh-Frames, Log |
| `ui/GimbalScreen.kt` | Compose-UI |

Protokollquelle: [Shalunizm/FeiyuTech-Pocket-Vlog-Protocol](https://github.com/Shalunizm/FeiyuTech-Pocket-Vlog-Protocol).

### Eigene Erkenntnisse (Mitschnitte vom 2026-11-22, FY_PocketV_3E)

Frame-Aufbau (`protocol/Frame.kt`):

```
A5 5A | route (2) | cmd (1) | flags (2) | len (2, LE) | payload (len) | CRC16 (2, LE)
```

| route | Richtung |
| --- | --- |
| `03 00` | Handy → Gimbal, Joystick |
| `03 02` | Handy → Gimbal, Anfrage (z. B. Home) |
| `02 03` | Gimbal → Handy, Antwort |
| `00 FF` | Gimbal → Handy, Broadcast |

| cmd | Bedeutung |
| --- | --- |
| `0E` | Joystick, Payload `00 X X Y Y` |
| `33` | Home. Der Gimbal antwortet mit `02 03 33`, flags `60 00`, und wiederholt die Payload `02` |
| `61` | Vermutlich Fahrtstatus nach Home: erst Payload `01`, wenn die Fahrt fertig ist `00` |
| `1E` | Telemetrie, etwa 2,4 Hz: `01 \| A int16 \| B int16 \| C int16 \| D u8` |

- **Achsen bestätigt:** A = Tilt, B = Roll, C = Pan, jeweils in 0,01°. Tilt-Anschläge liegen bei etwa +27° und −32°. Pan folgt dem Griff (Follow-Modus), Home zentriert relativ zum Griff. D liegt bei 79/80, unklar ob Akku oder Temperatur.
- **Richtung:** Aus Bedienersicht (Blick auf Klemme und Tasten, also auf die **Displayseite**) laufen beide Joystick-Rohachsen verkehrt herum. `FeiyuProtocol.move(panRight, tiltUp)` gleicht das aus, `Telemetry.pan`/`tilt` haben dieselben Vorzeichen.
- **Geschwindigkeit:** Bei kleinen Werten ungefähr linear, etwa 0,22°/s pro Einheit, also 51 ≈ 11°/s und 200 ≈ 45°/s. Bei 1000 sind es nur etwa 60°/s, und der Gimbal läuft nach dem Stopp-Frame kurz nach.
- **Notifications sind nicht frame-ausgerichtet:** Frames werden über mehrere Notifications verteilt oder aneinandergeklebt. `FrameAssembler` setzt sie wieder zusammen.

Live-Log vom Handy: `adb logcat -s SmartGimbal`. Telemetrie erscheint dort immer als `TELEM pan=… tilt=… roll=… d=…`.

## Was der Test am Gerät klären soll

- [x] Verbindung klappt, TX/RX-UUIDs stimmen
- [x] Richtung: beide Rohachsen invertiert, behoben über `move()`
- [x] Wertebereich: ungefähr linear bis ~200, Sättigung bei ~60°/s
- [ ] Muss der Joystick-Befehl dauerhaft gesendet werden, oder reicht ein einzelnes Paket?
- [x] Was schickt der Gimbal über RX: Telemetrie `1E`, Home-ACK, `61`
- [x] Funktioniert Home (`0x33`): ja

Fehlende Befehle (Halterung drehen, Modi) lassen sich mit einem Bluetooth-HCI-Snoop-Log mitschneiden, während Feiyu ON läuft. Den Log aktiviert man in den Entwickleroptionen, danach `adb bugreport`.
