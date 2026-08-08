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

**Phase 2 – Android Watch Next ist funktional abgeschlossen. Phase 3 – TMDB ist aktiv im Gerätetest.**

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
- Local-First-Anreicherung: Watch Next wird sofort mit Android-Quelldaten angezeigt und danach progressiv über TMDB angereichert
- keine feste Begrenzung der TMDB-Anreicherung auf die ersten Watch-Next-Einträge; alle sichtbaren Quellen werden in kleinen Batches verarbeitet
- einmaliger Retry für nach dem ersten Durchlauf noch nicht angereicherte Einträge, wobei negative No-Match-Caches unnötige erneute Netzwerkanfragen verhindern
- Beibehaltung von Watch-Next-Reihenfolge, Quellenfilter und Quell-Deep-Link
- Detailseite: normales OK startet weiterhin direkt die Quelle; INFO bzw. lange OK öffnet Details
- gespeicherte Watch-Next-Scrollposition beim Wechsel in/aus Details
- explizite Focus-Rückgabe nach Details an dieselbe stabile Watch-Next-Source-ID
- TMDB-Diagnose für Build-Aktivierung, TMDB-ID, Typ und Resolver-Confidence
- TMDB-Attribution im Bereich `Über / Credits` mit genehmigtem TMDB-Logo und vorgeschriebenem Hinweis
- Unit-Tests für Parser, Confidence, Medienmapping und Artwork-Priorität

Der TMDB-Token wird **nicht** im Repository abgelegt. Der Code akzeptiert `IL_TMDB_READ_ACCESS_TOKEN` bzw. die Gradle-Property `tmdbReadAccessToken`. Der signierte Development-Publisher konsumiert `IL_TMDB_READ_ACCESS_TOKEN` ausschließlich als GitHub-Secret. Für Phase 3 ist der Publisher strikt: fehlt das Secret, wird kein source-only Development-Build veröffentlicht. Das veröffentlichte `update.json` enthält nur `tmdbConfigured=true/false`, niemals den Secret-Wert.

TMDB verlangt für API-Nutzung ein genehmigtes Logo in einem About-/Credits-Bereich sowie den Hinweis „This product uses the TMDB API but is not endorsed or certified by TMDB.“ Beides ist umgesetzt.

Aktueller signierter Build mit **aktiver TMDB-Anreicherung**: **`0.1.0-dev.45` (`26000045`)**, `updateCompatible=true`, `tmdbConfigured=true`.

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
- `dev.40`: Focus kehrt nach Back aus Details auf exakt dieselbe Watch-Next-Karte zurück
- aktive TMDB-Anreicherung funktioniert grundsätzlich für Filme und viele Serien/Episoden

Der Focus-Fehler des ersten Detailseiten-Gerätetests ist damit behoben und auf realer TCL-Hardware bestätigt.

Im Gerätetest zeigte sich anschließend, dass nicht alle grundsätzlich auflösbaren Serien angereichert wurden. Die Ursache war kein weiteres Titel-Formatproblem, sondern eine feste Begrenzung auf die ersten 12 Watch-Next-Einträge sowie ein erst nach Abschluss der gesamten Gruppe sichtbares Ergebnis. `dev.45` entfernt diese Begrenzung, verarbeitet alle Einträge progressiv in kleinen Batches und versucht nach einem kurzen Abstand einmalig noch ungelöste Einträge erneut.

Für `dev.45` ist noch der reale TCL-Retest der vollständigen progressiven TMDB-Anreicherung, Serien-/Episodenauflösung, Artwork-Auswahl und Room-Cache-Nutzung erforderlich. Erst danach gilt Phase 3 als abgeschlossen.

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
