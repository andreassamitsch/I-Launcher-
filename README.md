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

**Phase 2 – Android Watch Next** ist in Gerätevalidierung.

Bereits umgesetzt:

- Android-TV-Home-/Launcher-Intent
- TCL-/Google-TV-Home-Fallback über Accessibility, real am Zielgerät bestätigt
- Compose-for-TV-Oberfläche mit zentralem kontrastreichem Dark Theme
- installierte TV-/Launcher-Apps und App-Start
- scrollbare Apps-Ansicht
- Android `TvProvider` / `WatchNextPrograms`
- unveränderte Übernahme der vom Provider gelieferten Watch-Next-Reihenfolge
- Watch-Next-Karten mit Quellbild, Staffel/Episode und Fortschritt
- Deep-Link zurück zur Quell-App
- Watch-Next-Diagnose mit Rohindex und Quellpaket
- `ContentObserver` für laufende TvProvider-Änderungen
- Unit-Tests für Mapping/Reihenfolge
- GitHub-CI für Tests und Debug-APK
- fester signierter Development-Updatekanal mit In-App-Updater

Noch offen für Phase 2: realer Vergleich der Watch-Next-Daten, Reihenfolge, Deep-Links und D-Pad-Navigation gegen Arc/CloudStream auf dem TCL Google TV.

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

## Lizenz

MIT
