# Phase 3: Offline-Sprachsteuerung

Stand: 2026-11-22, nach dem Merge von Phase 2 (`8da012a`).

## Entscheidungen

| Thema | Entscheidung |
| --- | --- |
| Erkenner | Vosk (`com.alphacephei:vosk-android:0.3.75`), vollständig offline |
| Modell | `vosk-model-small-de-0.15` (46,5 MB Zip, 95,6 MB entpackt, Apache 2.0), wird in die APK gepackt |
| Befehle | „Aufnahme starten", „Stopp", „Tracking an", „Tracking aus", „Gimbal zurück" |
| Rückmeldung | kurze Vibration und Hinweis im Bild, kein Ton (der wäre sonst in der Tonspur) |

## Modell im Projekt

- **Nicht in Git**, wegen der knapp 100 MB. Das Skript `scripts/fetch-vosk-model.sh` lädt das Zip, prüft die SHA-256 und entpackt es nach `app/src/main/assets/model-de/`, inklusive der `uuid`-Datei, die Vosks `StorageService` erwartet.
  - SHA-256: `b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43`
- `app/src/main/assets/model-de/` steht in `.gitignore`. Ein Gradle-Check bricht den Build mit einem klaren Hinweis auf das Skript ab, falls das Modell fehlt.
- Beim ersten Start entpackt die App das Modell aus den Assets in den App-Speicher. Das dauert einmalig ein paar Sekunden.

## Der Knackpunkt: ein Mikrofon, zwei Abnehmer

Während einer Aufnahme brauchen die Tonspur von CameraX und Vosk gleichzeitig das Mikrofon. Ab Android 10 gibt es dafür eine Priorität: Normalerweise bekommt nur ein Aufnehmer Ton, der andere Stille ([Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input)). CameraX nutzt die Quelle `CAMCORDER`, die als datenschutzsensitiv gilt. Für zwei Aufnehmer in **derselben** App ist nichts dokumentiert, ein Entwickler berichtet, dass beide Ton bekommen ([CameraX-Gruppe](https://groups.google.com/a/android.com/g/camerax-developers/c/I3JWhRazjQs)).

Deshalb zuerst ein **Spike am Gerät**:
- Vosk mit eigenem `AudioRecord` (Quelle `VOICE_RECOGNITION`, 16 kHz mono) parallel zu einer CameraX-Aufnahme.
- Gemessen wird der Pegel (RMS), den Vosk bekommt, und ob die Tonspur des Videos Ton enthält.

**Weg A (wenn beide Ton bekommen):** Beide Recorder laufen nebeneinander. Das ist die einfachste Lösung.

**Weg B (wenn einer Stille bekommt):** Ein einziges `AudioRecord` in der App verteilt den Ton an zwei Abnehmer:
- **Vosk:** erhält die Samples per `acceptWaveForm`.
- **Tonspur:** Ein eigener AAC-Encoder (`MediaCodec`) schreibt sie in eine temporäre Datei.
- **Video:** CameraX nimmt ohne Ton auf.
- **Zusammenführen:** Nach dem Stopp führt `MediaMuxer` Video und Ton zu einer MP4 zusammen, die Zeitstempel werden am Aufnahmestart ausgerichtet.

Das ist deutlich mehr Code, funktioniert aber unabhängig von der Mikrofon-Priorität.

## Erkennung

- **Feste Grammatik:** `["aufnahme starten", "stopp", "tracking an", "tracking aus", "gimbal zurück", "[unk]"]`. Alles andere landet in `[unk]`. Das senkt Fehlauslöser und Rechenlast.
- **Konfidenz:** `setWords(true)` liefert eine Konfidenz pro Wort. Ein Befehl zählt nur, wenn jedes Wort mindestens den Schwellwert erreicht (Start 0,7, im Debug-Screen einstellbar).
- **Zustand und Sperre:**
  - „Aufnahme starten" wirkt nur ohne laufende Aufnahme, „Stopp" nur während einer Aufnahme.
  - Derselbe Befehl wird 1,5 s lang nicht wiederholt ausgelöst.
  - „Tracking an" und „Gimbal zurück" brauchen einen verbundenen Gimbal.
- **Zuhören:** nur, solange der Kamera-Screen sichtbar ist und "Sprache" eingeschaltet ist. Android zeigt dann seinen Mikrofon-Hinweis.
- **Log:** Pro Ergebnis landet eine `VOICE`-Zeile in logcat, mit Text, Konfidenz, Aktion oder Grund für das Ignorieren.

## Arbeitsschritte

1. **Modell:** Skript, Assets, `.gitignore`, Gradle-Check, Vosk-Abhängigkeit.
2. **Spike Mikrofon:** Vosk und CameraX parallel, Pegel und Tonspur messen. Danach entscheiden: Weg A oder Weg B.
3. **Erkennung:** `VoiceCommandParser` (reines Kotlin, Unit-Tests für JSON-Auswertung, Schwellwert, Sperre, Zustand) und `VoiceController` (Modell laden, Recognizer, Zuhören starten und stoppen).
4. **Einbindung:** Befehle auf Aufnahme, Tracking und Home abbilden, Vibration und Hinweis im Bild, Button "Sprache an/aus", Schwellwert im Debug-Screen.
5. **Nur bei Weg B:** gemeinsames `AudioRecord`, AAC-Encoder, Zusammenführen nach der Aufnahme.
6. **Test am Gerät:**
   - Befehle in Ruhe und mit Hintergrundgeräusch, aus 1 bis 3 m Abstand, auch während einer Aufnahme.
   - Fehlauslöser über 2 Minuten normales Reden.
   - Tonspur der Aufnahme anhören.

## Risiken

- **Erkennung auf Distanz:** Das kleine Modell ist auf Handy-Nähe trainiert. Gegenmittel sind kurze, gut unterscheidbare Befehle, die Grammatik und notfalls ein niedrigerer Schwellwert mit längerer Sperre.
- **Fehlauslöser:** „Stopp" ist kurz und kommt im normalen Reden vor. Falls es zu oft auslöst, wird daraus „Aufnahme stoppen".
- **Größe:** Die APK wächst um etwa 50 MB, für eine private App vertretbar.
- **Wärme:** Vosk, 4K-Encoding und Gesichtserkennung laufen gleichzeitig. Der Temperatur-Log aus Phase 2 zeigt, ob das stört.
