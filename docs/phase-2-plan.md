# Phase 2: Kamera, Face-Tracking, Regler

Stand: 2026-11-22, nach Abschluss von Phase 1 (Commit `879bd05`).

## Entscheidungen

| Thema | Entscheidung |
| --- | --- |
| Kamera | Haupt- und Frontkamera, umschaltbar. Die Richtungsvorzeichen gelten pro Kamera. |
| Aufnahme | 4K/30 als Standard, Fallback auf 1080p, falls das Gerät die Kombination nicht schafft |
| Ziel bei mehreren Gesichtern | Am zuerst erfassten Gesicht (Tracking-ID) bleiben. Ist es verloren, das größte nehmen. |
| App-Aufbau | Kamera ist der Hauptscreen, der BLE-Test bleibt als Debug-Screen |

## Technische Leitplanken

- **CameraX über `LifecycleCameraController`:** Preview, ImageAnalysis und VideoCapture laufen über einen Controller. Das Umschalten der Kamera ist dann eine Zeile, und Drehung und Spiegelung übernimmt der Controller.
- **Austauschbarer Gesichtsdetektor:** Ein eigener `ImageAnalysis.Analyzer` übergibt jedes Bild an ein `FaceDetector`-Interface. Regler und Overlay hängen nur an diesem Interface, nicht an ML Kit.

  ```kotlin
  interface FaceDetector : Closeable {
      /** Gesichter im aufrecht gedrehten Analysebild, Koordinaten normiert auf 0..1. */
      fun detect(image: ImageProxy): List<DetectedFace>
  }
  data class DetectedFace(val trackingId: Int?, val box: RectF)
  ```

  Das Overlay rechnet die normierten Koordinaten über `CoordinateTransform` (camera-view) und `PreviewView.outputTransform` in Preview-Koordinaten um. Das funktioniert für beide Backends gleich, auch mit Spiegelung bei der Frontkamera.
- **Backend 1, Standard: ML Kit Face Detection (gebündeltes Modell)** mit `PERFORMANCE_MODE_FAST`, `enableTracking()` und einem Analyse-Stream von etwa 640×480. Es läuft auf der CPU und liefert eigene Tracking-IDs.
- **Backend 2, Option: MediaPipe Tasks Face Detector (BlazeFace short-range) mit `Delegate.GPU`.** Das ist der realistische Hardware-Beschleuniger auf dem Pixel 7. Es kommt nur, wenn ML Kit im Test zu viel CPU oder Wärme kostet. MediaPipe vergibt keine Tracking-IDs, deshalb bekommt der `FaceTracker` dafür eine eigene IoU-Zuordnung über die Frames.
- **Keine Tensor-TPU auf dem Pixel 7:** Das Tensor SDK (LiteRT, `Accelerator.NPU`) unterstützt nur Tensor G5/G6 ab dem Pixel 10, und NNAPI ist seit Android 15 deprecated. Mit dem Interface wäre ein späteres LiteRT-NPU-Backend für neuere Pixel nur eine weitere Implementierung.
- **Spec-Korrektur zu HDR:** CameraX Extensions gelten nicht für VideoCapture. HDR-Video geht stattdessen über 10-Bit-HLG (`DynamicRange.HLG_10_BIT`). Das ist optional für Phase 4, Phase 2 nimmt SDR auf.
- **Ausrichtung:** Die Activity darf sich drehen (Halterung im Hoch- oder Querformat). Mit `configChanges` wird sie dabei nicht neu erzeugt, sonst bräche eine laufende Aufnahme ab.

## Regelung

Die Gimbal-Daten stammen aus den Mitschnitten von Phase 1: Die Geschwindigkeit liegt bei etwa 0,22°/s pro Joystick-Einheit, linear bis etwa 250 Einheiten (~55°/s). Darüber sättigt sie und läuft nach.

1. **Fehler bestimmen:** Gesichtsmitte relativ zur Bildmitte, normiert auf −1…1. Mit dem Bildwinkel der Kamera (aus `CameraCharacteristics`) wird daraus ein Winkelfehler in Grad.
2. **Glätten:** EMA auf die Gesichtsposition, damit Detektionsrauschen den Gimbal nicht zittern lässt.
3. **Totzone:** 3° um die Mitte, innerhalb davon kein Nachregeln.
4. **PD-Regler pro Achse:** Er rechnet Grad Abweichung in °/s Sollgeschwindigkeit um und dann in Joystick-Einheiten (÷ 0,22). Startwert Kp ≈ 2/s, damit ist die Hälfte des Fehlers nach etwa 0,35 s weg.
5. **Begrenzen:** maximal ±250 Einheiten (linearer Bereich, kein Nachlaufen) und eine Rampe gegen ruckartige Starts.
6. **Tilt-Anschläge:** Die Telemetrie meldet etwa +27° und −32°. Nahe am Anschlag wird nicht weiter in diese Richtung gedrückt.
7. **Gesicht verloren:** Sofort Ausgabe 0, Position halten. Kommt das Gesicht nach 1 s nicht zurück, wird das größte neue Gesicht zum Ziel.
8. **Vorrang beim Senden:** Die bestehende 20-Hz-Schleife sendet das Steuerkreuz vor dem Tracker, den Tracker nur, wenn er aktiv ist. Ist die Tracker-Ausgabe älter als 300 ms, wird gestoppt.

Die Vorzeichen pro Kamera (Gesicht rechts im Bild bedeutet Pan rechts oder links) werden im Test bestätigt und sind im Debug-Screen umschaltbar.

## Arbeitsschritte

1. **Umbau:** BLE-Client, Send-Schleife und Telemetrie wandern aus dem `GimbalViewModel` in eine gemeinsame `GimbalSession`, die beide Screens nutzen. Der Vorrang zwischen Steuerkreuz und Tracker kommt dazu, außerdem eine einfache Navigation Kamera ↔ Debug.
2. **Kamera-Screen:** Berechtigungen für Kamera und Mikrofon, Vollbild-Preview, Umschalten Haupt-/Frontkamera, Gimbal-Status in der Ecke.
3. **Gesichtserkennung:** `FaceDetector`-Interface mit dem ML-Kit-Backend, eigener Analyzer mit Messung der Analysezeit pro Frame (in logcat), Zielauswahl über Tracking-ID mit IoU-Fallback, Overlay mit Ziel- und Nebenboxen.
4. **Regler:** reines Kotlin (`AxisController`, `FaceTracker`), Tracking ein/aus. Die Parameter Kp, Totzone und Max-Speed lassen sich im Debug-Screen einstellen.
5. **Aufnahme:** `QualitySelector` mit UHD und Fallback FHD, Speichern in MediaStore (`Movies/SmartGimbal`) mit Ton, Aufnahme-Button mit Timer. Während der Aufnahme ist das Kamera-Umschalten gesperrt.
6. **Test am Gerät:** Vorzeichen pro Kamera bestätigen, Regler tunen (Überschwingen, Zittern), Last und Temperatur bei 4K/30 mit Analyse prüfen.
7. **Nur bei Bedarf, MediaPipe-GPU-Backend:** wenn Schritt 6 zu viel CPU-Last oder Wärme zeigt. Umschaltbar im Debug-Screen, Vergleich der Analysezeit und Temperatur zwischen beiden Backends.

## Tests

- **Unit-Tests ohne Gerät:**
  - Zielauswahl: ID-Wechsel, verlorenes Gesicht, IoU-Zuordnung ohne IDs
  - Bildwinkel-Umrechnung
  - Regler gegen ein simuliertes Gimbal-Modell (Winkel integriert Geschwindigkeit × 0,22 plus ~100 ms Latenz): Er muss in die Mitte laufen, ohne zu überschwingen, und in der Totzone ruhig bleiben.
- **Am Gerät:** Log über `adb logcat -s SmartGimbal` mit Fehler, Ausgabe und Telemetrie pro Frame, damit das Tuning aus Daten statt nach Gefühl passiert.

## Risiken

- **Gleichzeitige Use-Cases:** Preview, Analyse und 4K-Video zusammen könnten auf der Frontkamera nicht gehen. Dann greift der Fallback auf FHD, und das sieht man im UI.
- **Wärme bei langen 4K-Aufnahmen mit Analyse:** Gegenmittel sind, in dieser Reihenfolge: die Analyse-Rate drosseln (zum Beispiel jeden zweiten Frame), dann auf das MediaPipe-GPU-Backend wechseln. Die GPU teilt sich die Arbeit allerdings mit der Kamera-Pipeline, ob das hilft, zeigt erst eine Messung.
- **Latenz Kamera → Detektion → BLE → Motor:** Wenn der Regler schwingt, wird Kp kleiner, oder die Telemetrie fließt als Vorhersage ein.
