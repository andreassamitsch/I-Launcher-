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

**Phase 4 – Trailer ist funktional abgeschlossen und auf realer TCL-Hardware bestätigt. Phase 5 – Gigablue/OpenWebif ist der nächste Entwicklungsabschnitt.**

Der bestätigte Unterbau umfasst inzwischen:

- Android-TV-Launcher/Home-App mit D-Pad-Focus
- Android Watch Next über TvProvider einschließlich CloudStream
- gemeinsames provider-neutrales `MediaItem`-/`MediaSource`-Modell
- konservative TMDB-Auflösung mit Room-Cache
- Film-/Serien-/Episodendaten und Artwork
- provider-neutrale Detailseite mit Focus-Rückgabe
- TMDB-Trailerauflösung mit YouTube-ID
- Episode-Trailer vor Serien-Trailer
- `Trailer` sowie `Trailer suchen` als Fallback
- Trailerstart per Android `ACTION_VIEW`
- Room-Migration von Phase 3 auf Phase 4 ohne Verlust bestehender Cache-Daten

Der TMDB-Token wird **nicht** im Repository abgelegt. Der signierte Development-Publisher konsumiert `IL_TMDB_READ_ACCESS_TOKEN` ausschließlich als GitHub-Secret.

Auf dem TCL verifiziert:

- Launcher-/Home-Funktion und D-Pad-Navigation
- Watch-Next-Reihenfolge, Quellenfilter und Deep-Links
- Direktstart und Detailseite
- Focus-Rückkehr nach Details
- TMDB-Anreicherung für Filme, Serien und Episoden
- Poster/Backdrops/Logos/Episodenbilder
- progressives Nachladen über die gesamte Watch-Next-Liste
- Update `dev.45` → `dev.47` einschließlich Room-Migration
- TMDB-Trailerbutton
- YouTube-Trailerstart
- Such-Fallback bei fehlendem TMDB-Trailer
- Rückkehr aus YouTube ohne Navigation-/Focus-Regression

Bestätigter Phase-4-Build: **`0.1.0-dev.47` (`26000047`)**, `updateCompatible=true`, `tmdbConfigured=true`.

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
