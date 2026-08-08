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

**Phase 3 – TMDB ist abgeschlossen und in `main` gemergt. Phase 4 – Trailer ist implementiert, gebaut und wartet auf den TCL-Gerätetest.**

Der bestätigte Phase-3-Unterbau umfasst das gemeinsame Medienmodell, Android Watch Next, konservative TMDB-Auflösung, Room-Cache, Film-/Serien-/Episodendaten, Bilder, provider-neutrale Details und die auf dem TCL verifizierte D-Pad-/Focus-Rückgabe.

Phase 4 ergänzt:

- TMDB-Video-Metadaten für Filme, Serien und Episoden
- deterministische Auswahl einer geeigneten YouTube-Trailer-ID
- Episode-Trailer vor Serien-Trailer
- provider-neutrale `TrailerRef` im gemeinsamen Medienmodell
- Room-Cache für Trailer-ID und „kein Trailer gefunden“-Status
- Datenbankmigration von Phase-3-Schema 1 auf Schema 2 ohne Löschen bestehender Caches
- `Trailer`-Aktion in der Detailseite
- Wiedergabe über Android `ACTION_VIEW` an YouTube/einen geeigneten Handler
- `Trailer suchen` als gezielter YouTube-Fallback, falls TMDB keine verwertbare Video-ID liefert
- keine YouTube-Data-API, kein zusätzlicher API-Key und keine YouTube-Stream-Extraktion
- Unit-Tests für Trailer-Auswahl und Episode-vor-Serie-Priorität

Der TMDB-Token wird **nicht** im Repository abgelegt. Der signierte Development-Publisher konsumiert `IL_TMDB_READ_ACCESS_TOKEN` ausschließlich als GitHub-Secret.

Auf dem TCL bereits aus Phase 3 verifiziert:

- Launcher-/Home-Funktion und D-Pad-Navigation
- Android Watch Next über TvProvider einschließlich CloudStream
- Reihenfolge, Quellenfilter und Deep-Links
- Direktstart per OK
- Detailseite per INFO/lange OK
- Focus-Rückkehr nach Details auf exakt dieselbe Watch-Next-Karte
- TMDB-Anreicherung für Filme, Serien und Episoden
- Poster/Backdrops/Logos/Episodenbilder
- progressives Nachladen über die gesamte sichtbare Watch-Next-Liste

Aktueller Phase-4-Testbuild: **`0.1.0-dev.47` (`26000047`)**, `updateCompatible=true`, `tmdbConfigured=true`. Unit-Tests und `assembleDebug` sind im signierten Publisher erfolgreich. Trailerstart, Such-Fallback, Rückkehrverhalten und die Room-Migration müssen noch auf dem realen TCL bestätigt werden.

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
