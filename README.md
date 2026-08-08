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

**Phase 2 – Android Watch Next ist funktional abgeschlossen. Phase 3 – TMDB startet.**

Bereits umgesetzt und auf dem TCL grundsätzlich verifiziert:

- Android-TV-Home-/Launcher-Intent inklusive normalem `MAIN`/`LAUNCHER`-Front-Door-Einstieg
- Home-Fallback über Accessibility bei ADB-Installation
- Compose-for-TV-Oberfläche mit zentralem kontrastreichem Dark Theme
- installierte TV-/Launcher-Apps und App-Start
- scrollbare Apps-Ansicht
- Android `TvProvider` / `WatchNextPrograms`
- Runtime-Berechtigung `android.permission.READ_TV_LISTINGS`
- Watch-Next-Sortierung nach `last_engagement_time_utc_millis DESC`
- Quellenfilter pro App/Package
- Watch-Next-Karten mit Quellbild, Staffel/Episode und Fortschritt
- Deep-Link zurück zur Quell-App
- Watch-Next-Diagnose mit Rohindex, Quellpaket, Typ und Engagement-Zeit
- `ContentObserver` für laufende TvProvider-Änderungen
- Unit-Tests für Mapping/Reihenfolge/Sortieranforderung
- GitHub-CI für Tests und Debug-APK
- fester signierter Development-Updatekanal mit In-App-Updater

Watch Next liefert auf dem Zielgerät unter anderem CloudStream-Einträge über die reguläre Android-TvProvider-Schnittstelle. Deshalb bleibt eine CloudStream-spezifische Integration weiterhin bewusst außen vor.

Das TCL-/Google-TV-Thema rund um Android 13+ `Covered Applications` / `Restricted Settings` bei lokal installierten APKs bleibt als separates Distributionsthema offen. Es blockiert die weiteren Content-Phasen nicht.

Phase 3 baut nun den gemeinsamen Medien-Unterbau und die TMDB-Anreicherung auf: API-Client, Resolver mit Confidence, lokaler Room-Cache, Bilder sowie Serien-/Episodendaten und Detailansicht.

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
