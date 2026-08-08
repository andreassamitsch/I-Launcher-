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

**Phase 2 – Android Watch Next ist funktional abgeschlossen. Phase 3 – TMDB ist in Entwicklung.**

Der aktuelle Phase-3-Unterbau enthält bereits:

- gemeinsames `MediaItem`-/`MediaSource`-Modell
- Android-Watch-Next-Typ und Release-Datum als Resolver-Hinweise
- Titel-/Jahr-/Staffel-/Episoden-Parser
- konservativen TMDB-Resolver mit Confidence-Schwelle
- Retrofit/OkHttp-Client mit Bearer-Read-Access-Token
- Room-Cache für Mappings, Medien- und Episodendaten einschließlich negativer Treffer
- Cache-Identitätsprüfung gegen Titel/Jahr/Staffel/Episode vor Wiederverwendung eines Source-Mappings
- 30-Tage-Refresh und 180-Tage-Hard-Limit für TMDB-Cache
- TMDB-Bildkonfiguration für Poster, Backdrops, Logos und Episode Stills
- Local-First-Anreicherung: Watch Next wird sofort mit Android-Quelldaten angezeigt und erst danach begrenzt über TMDB angereichert
- Beibehaltung von Watch-Next-Reihenfolge, Quellenfilter und Quell-Deep-Link
- Detailseite: normales OK startet weiterhin direkt die Quelle; INFO bzw. lange OK öffnet Details
- gespeicherte Watch-Next-Scrollposition beim Wechsel in/aus Details
- explizite Focus-Rückgabe nach Details an dieselbe stabile Watch-Next-Source-ID
- TMDB-Diagnose für Build-Aktivierung, TMDB-ID, Typ und Resolver-Confidence
- TMDB-Attribution im Bereich `Über / Credits` mit genehmigtem TMDB-Logo und vorgeschriebenem Hinweis
- Unit-Tests für Parser, Confidence, Medienmapping und Artwork-Priorität

Der TMDB-Token wird **nicht** im Repository abgelegt. Der Code akzeptiert `IL_TMDB_READ_ACCESS_TOKEN` bzw. die Gradle-Property `tmdbReadAccessToken`. Der signierte Development-Publisher konsumiert `IL_TMDB_READ_ACCESS_TOKEN` ausschließlich als GitHub-Secret und veröffentlicht in `update.json` zusätzlich `tmdbConfigured=true/false`. Ohne Token läuft I Launcher weiterhin vollständig mit den Android-Quelldaten wie in Phase 2.

TMDB verlangt für API-Nutzung ein genehmigtes Logo in einem About-/Credits-Bereich sowie den Hinweis „This product uses the TMDB API but is not endorsed or certified by TMDB.“ Beides ist vor der Live-Aktivierung umgesetzt.

Bereits auf dem TCL grundsätzlich verifiziert:

- Android-TV-Home-/Launcher-Intent
- Accessibility-Home-Fallback bei ADB-Installation
- kontrastreiches Compose-for-TV-Dark-Theme
- installierte Apps und App-Start
- Android `TvProvider` / `WatchNextPrograms`
- Runtime-Berechtigung `android.permission.READ_TV_LISTINGS`
- Watch-Next-Sortierung nach `last_engagement_time_utc_millis DESC`
- Quellenfilter pro App/Package
- Deep-Links zurück zur Quell-App
- normales OK auf Watch Next startet direkt die Quelle
- INFO/lange OK öffnet die Detailseite und Back kehrt zu Home zurück

Beim ersten Detailseiten-Gerätetest ging der Focus nach Back verloren und landete effektiv wieder oben in der Navigation. Die Ursache ist behoben, indem nicht nur der LazyList-State, sondern zusätzlich die stabile Source-ID der geöffneten Karte gespeichert wird. Nach Back wird die Zielkarte zuerst wieder sichtbar gemacht und anschließend im folgenden Compose-Frame explizit fokussiert. Dieser Fix benötigt noch den abschließenden TCL-Retest.

Watch Next liefert auf dem Zielgerät unter anderem CloudStream-Einträge über die reguläre Android-TvProvider-Schnittstelle. Deshalb bleibt eine CloudStream-spezifische Integration bewusst außen vor.

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
