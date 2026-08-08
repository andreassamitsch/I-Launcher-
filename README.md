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

**Phase 3 – TMDB ist funktional abgeschlossen und auf realer TCL-Hardware bestätigt. Phase 4 – Trailer startet als nächster Entwicklungsabschnitt.**

Der abgeschlossene Phase-3-Unterbau enthält:

- gemeinsames `MediaItem`-/`MediaSource`-Modell
- Android-Watch-Next-Typ und Release-Datum als Resolver-Hinweise
- Titel-/Jahr-/Staffel-/Episoden-Parser inklusive `Sx:Ex`-Quelltiteln
- konservativen TMDB-Resolver mit Confidence-Schwelle
- Retrofit/OkHttp-Client mit Bearer-Read-Access-Token
- Room-Cache für Mappings, Medien- und Episodendaten einschließlich negativer Treffer
- Cache-Identitätsprüfung gegen Titel/Jahr/Staffel/Episode vor Wiederverwendung eines Source-Mappings
- 30-Tage-Refresh und 180-Tage-Hard-Limit für TMDB-Cache
- TMDB-Bildkonfiguration für Poster, Backdrops, Logos und Episode Stills
- Local-First-Anreicherung: Watch Next wird sofort mit Android-Quelldaten angezeigt und anschließend progressiv über TMDB angereichert
- Verarbeitung aller sichtbaren Watch-Next-Einträge in kleinen Batches ohne feste 12-Einträge-Grenze
- einmaliger Retry für noch nicht angereicherte Einträge; negative No-Match-Caches verhindern unnötige Wiederholungsanfragen
- Beibehaltung von Watch-Next-Reihenfolge, Quellenfilter und Quell-Deep-Link
- Detailseite: normales OK startet weiterhin direkt die Quelle; INFO bzw. lange OK öffnet Details
- gespeicherte Watch-Next-Scrollposition und explizite Focus-Rückgabe an dieselbe stabile Source-ID
- TMDB-Diagnose ohne Secrets oder vollständige private URLs
- TMDB-Attribution im Bereich `Über / Credits`
- Unit-Tests für Parser, Confidence, Medienmapping und Artwork-Priorität

Der TMDB-Token wird **nicht** im Repository abgelegt. Der signierte Development-Publisher konsumiert `IL_TMDB_READ_ACCESS_TOKEN` ausschließlich als GitHub-Secret und veröffentlicht keinen aktiven TMDB-Build, wenn das Secret fehlt.

Auf dem TCL verifiziert:

- Launcher-/Home-Funktion und D-Pad-Navigation
- Android Watch Next über TvProvider einschließlich CloudStream
- Reihenfolge, Quellenfilter und Deep-Links
- Direktstart per OK
- Detailseite per INFO/lange OK
- Focus-Rückkehr nach Details auf exakt dieselbe Watch-Next-Karte
- TMDB-Anreicherung für Filme, Serien und Episoden
- Poster/Backdrops/Logos/Episodenbilder
- progressives Nachladen über die gesamte sichtbare Watch-Next-Liste

Aktueller bestätigter Phase-3-Build: **`0.1.0-dev.45` (`26000045`)**, `updateCompatible=true`, `tmdbConfigured=true`.

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
