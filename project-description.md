---
created: 2026-11-22T10:55
tags: [esp32]
---
# Smart Gimbal Controller für Google Pixel 7
**Projektkonzept & Spezifikation:** Autonomes Face-Tracking & Sprachsteuerung für den FeiyuTech VLOG pocket

---

## 1. Projektziel & Vision

Ziel dieses Projekts ist die Entwicklung einer maßgeschneiderten nativen Android-App für das **Google Pixel 7**, die den 3-Achsen-Gimbal **FeiyuTech VLOG pocket** über Bluetooth Low Energy (BLE) ansteuert. Die App ersetzt die Hersteller-Software (*Feiyu ON*) durch eine schlanke All-in-One-Lösung, die hochauflösende Videoaufnahmen mit lokaler Echtzeit-Gesichtsverfolgung und freihändiger Sprachsteuerung kombiniert.

### Kernfunktionen der Anwendung
* **Echtzeit-Face-Tracking:** Automatische Verfolgung des Gesichts durch aktive Steuerung der Pan- und Tilt-Motoren des VLOG pocket.
* **Freihändige Sprachsteuerung:** Starten und Stoppen der Videoaufnahme per gesprochenem Befehl (z. B. „Aufnahme starten“ / „Stopp“) ohne Berührung des Displays.
* **Hochwertige Videoaufnahme:** Direkte Integration der Kamera-Pipeline auf dem Pixel 7 (bis zu 4K, 60 fps, inklusive HDR-Unterstützung), da eine parallele Nutzung mit der externen Google Kamera-App technisch ausgeschlossen ist.

---

## 2. Systemarchitektur & Technologie-Stack

Die Architektur nutzt die Hardware-Vorteile des Google Pixel 7 (insbesondere den *Google Tensor G2* Chip mit integrierter TPU) optimal aus, um Bildverarbeitung, KI-Erkennung und Bluetooth-Kommunikation latenzarm parallel auszuführen.

| Modul | Technologie / API | Aufgabe im System |
| :--- | :--- | :--- |
| **App-Basis** | Android SDK (Kotlin) | Native Android-Anwendung, Lifecycle-Management, Berechtigungen (Kamera, Mikrofon, Bluetooth). |
| **Kamera & Video** | Android CameraX + CameraX Extensions | 4K-Videoaufzeichnung (`VideoCapture`) mit Zugriff auf Pixel-HDR-Algorithmen sowie paralleler Preview-Stream für die Analyse. |
| **Gesichtserkennung** | Google ML Kit (Face Detection) | Hardwarebeschleunigte Erkennung der Gesichts-Bounding-Box auf einem 640×480-Analysestream in Echtzeit. |
| **Regelkreis** | Eigener PID-Controller (Kotlin) | Berechnung der Abweichung zwischen Gesichtsmitte und Bildzentrum; Umrechnung in weiche Winkelgeschwindigkeiten. |
| **Gimbal-Schnittstelle** | Android BLE (`BluetoothGatt`) | Aufbau der GATT-Verbindung zum VLOG pocket und Senden der proprietären Hex-Steuerbefehle (Pan/Tilt) alle 40–60 ms. |
| **Sprachsteuerung** | Vosk Offline ASR oder Android `SpeechRecognizer` | Kontinuierliches Zuhören im Hintergrund während der Aufnahme zur Erkennung von Start-/Stopp-Schlüsselwörtern. |

---

## 3. Technische Rahmenbedingungen & Besonderheiten

* **Kamera-Exklusivität gelöst durch All-in-One-Ansatz:** Da Android nur einer App gleichzeitig den Zugriff auf den Kamerasensor erlaubt, übernimmt die App selbst die Videoaufnahme. Auf dem Pixel 7 stehen über *CameraX Extensions* dennoch HDR- und Schwachlicht-Optimierungen bereit.
* **Reverse-Engineered BLE-Protokoll:** Da FeiyuTech kein öffentliches SDK bereitstellt, basiert die Motorsteuerung auf den entschlüsselten GATT-Characteristics und Hex-Byte-Paketen der Community-Dokumentation (*FeiyuTech-Pocket-Vlog-Protocol*).
* **Begrenzung der Paketrate:** Steuerbefehle werden auf maximal 15–25 Hz (alle 40–60 ms) gedrosselt, um den Bluetooth-Controller des Gimbals nicht zu überlasten und Verzögerungen zu vermeiden.
* **Energieeffizienz:** Durch die Auslagerung der Gesichtserkennung auf die Tensor-TPU bleibt die CPU-Last gering, was Überhitzung bei längeren 4K-Aufnahmen verhindert.

---

## 4. Geplanter Umsetzungsplan (Meilensteine)

### Phase 1: BLE-Verbindung & Motorsteuerung (Prototyp)
Scannen und Verbinden mit dem VLOG pocket via Bluetooth LE; Senden manueller Test-Pakete (Links/Rechts/Hoch/Runter) zur Verifizierung der Hex-Befehle und Checksummen.

### Phase 2: Kamera-Pipeline & Face-Tracking-Regelkreis
Einrichten von CameraX (`Preview` + `ImageAnalysis` + `VideoCapture`); Integration von ML Kit Face Detection; Implementierung und Feintuning des PID-Reglers für flüssige Schwenks ohne Nachschwingen.

### Phase 3: Offline-Sprachsteuerung & Audio-Routing
Integration der Spracherkennung (z. B. Vosk) für zuverlässige Start-/Stopp-Befehle; Sicherstellen, dass Mikrofon-Zugriff für Sprachbefehle und Video-Tonspur konfliktfrei zusammenarbeiten.

### Phase 4: Praxistest & Feinschliff
Tests bei unterschiedlichen Lichtverhältnissen und Bewegungsgeschwindigkeiten; Optimierung der Totzone (Deadzone) in der Bildmitte, damit das Gimbal bei ruhigem Stehen nicht unnötig nachregelt.

---

*Projekt-Spezifikation • Zielplattform: Google Pixel 7 (Android) • Hardware: FeiyuTech VLOG pocket*