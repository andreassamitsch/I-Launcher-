# I Launcher

I Launcher ist ein werbefreier, content-zentrierter Android-TV-Launcher in Kotlin und Compose for TV.

## Ziele

- schnelle TV-Home-Oberfläche ohne Werbung
- Android Watch Next / „Weiterschauen“
- Preview Channels installierter Apps
- TMDB-Metadaten für Filme, Serien und Episoden
- Trailer über TMDB/YouTube
- direkte Gigablue-X3-/Enigma2-/OpenWebif-Integration
- EPG mit Bildern und später integriertem Live-TV-Player
- vollständige D-Pad-/Fernbedienungsbedienung
- Local First

## Status

**Phase 4 – Trailer ist abgeschlossen. Phase 5 – Gigablue/OpenWebif ist implementiert, erfolgreich gebaut und wartet auf den realen Gigablue-Gerätetest.**

Der bestätigte Unterbau umfasst:

- Android-TV-Launcher/Home-App mit D-Pad-Focus
- Android Watch Next über TvProvider einschließlich CloudStream
- gemeinsames provider-neutrales `MediaItem`-/`MediaSource`-Modell
- konservative TMDB-Auflösung mit Room-Cache
- Film-/Serien-/Episodendaten und Artwork
- provider-neutrale Detailseite mit Focus-Rückgabe
- TMDB-/YouTube-Trailer mit Such-Fallback

Phase 5 ergänzt direkt über Enigma2/OpenWebif:

- lokale Receiver-Konfiguration mit optionaler HTTP-Basic-Authentifizierung
- Bouquets und Sender über OpenWebif
- Picons
- EPG Now/Next
- auswählbares Bouquet
- Local-First-Snapshot für die zuletzt bekannten TV-Daten
- eigene `Live TV`-Ansicht für Einrichtung und Diagnose
- `Jetzt im TV` auf Home mit Senderlogo, aktueller Sendung, Zeit, Fortschritt und nächster Sendung
- fünfminütige Hintergrundaktualisierung ohne den Launcher-Start zu blockieren

OpenWebif-Zugangsdaten werden nur lokal gespeichert, nicht geloggt und durch `allowBackup=false` nicht über Android Auto Backup ausgelagert. Normale lokale OpenWebif-Installationen über HTTP werden unterstützt; bei Verwendung von HTTP-Basic sollten Zugangsdaten nur im vertrauenswürdigen Heimnetz verwendet werden.

Auf dem TCL bereits verifiziert sind Launcher, Watch Next, TMDB, Detailnavigation, Focus-Rückgabe und Trailer einschließlich Update `dev.45` → `dev.47`.

Bestätigter Phase-4-Build: **`0.1.0-dev.47` (`26000047`)**.

Aktueller Phase-5-Testbuild: **`0.1.0-dev.51` (`26000051`)**, `updateCompatible=true`, `tmdbConfigured=true`. Unit-Tests und `assembleDebug` sind im signierten Publisher erfolgreich durchgelaufen. Phase 5 gilt erst nach dem realen TCL + Gigablue-X3-Test als abgeschlossen.

Watch Next liefert CloudStream-Einträge über die reguläre Android-TvProvider-Schnittstelle. Eine CloudStream-spezifische Integration bleibt deshalb bewusst außen vor.

Das TCL-/Google-TV-Thema rund um Android 13+ `Covered Applications` / `Restricted Settings` bei lokal installierten APKs bleibt als separates Distributionsthema offen und blockiert die Content-Phasen nicht.

Siehe:

- [`AGENTS.md`](AGENTS.md) – verbindliche Entwicklungsrichtlinien
- [`ROADMAP.md`](ROADMAP.md) – Entwicklungsphasen
- [`ARCHITECTURE.md`](ARCHITECTURE.md) – Architektur

## Stack

Kotlin · Jetpack Compose · Compose for TV · AndroidX · Coroutines/Flow · Room · Hilt · Retrofit/OkHttp · Coil · Media3

## Build-Basis

- Android Gradle Plugin 9.3.1
- Gradle 9.5.0 (CI)
- compileSdk 36
- targetSdk 36
- minSdk 26
- Compose BOM 2026.06.00
- Compose for TV 1.1.0
- Coil 3.5.0
- Room 2.8.4
- Retrofit 3.0.0
- OkHttp 5.3.0

## Lizenz

MIT
