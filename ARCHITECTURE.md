# I Launcher Architecture

`AGENTS.md` ist verbindlich. Dieses Dokument beschreibt die aktuelle technische Zielarchitektur und wird mit dem Projekt weiterentwickelt.

## Architekturprinzipien

- Content-first statt App-first
- Local First
- Datenquellen hinter Provider-/Repository-Grenzen
- UI kennt möglichst keine externen API-Details
- D-Pad und Focus als Kernanforderung
- lokale Daten zuerst, Netzwerkupdates danach
- keine app-spezifischen Integrationen, wenn Android-Standardschnittstellen ausreichen
- unsichere Metadaten-Treffer dürfen Quelldaten nicht überschreiben
- Trailerauflösung nutzt vorhandene Metadaten vor zusätzlichen Such-APIs

## Aktueller Stand: Phase 4

Die Gradle-Struktur bleibt vorerst bewusst bei einem `app`-Modul. Die Provider-Grenzen sind paketweise getrennt:

```text
app/
  data/apps/       installierte Apps / PackageManager
  data/tv/         Android TvProvider / Watch Next / Quellenpräferenzen / Medienmapping
  data/tmdb/       TMDB API / Parser / Resolver / Metadaten / Trailer-Metadaten
  data/youtube/    YouTube-Wiedergabe und Such-Fallback über Android-Intents
  data/database/   Room / TMDB-Mappings / Medien-, Episoden- und Trailer-Cache
  data/update/     Development-Updatekanal
  model/           WatchNextItem + gemeinsames MediaItem/MediaSource/Trailer-Modell
  system/          Home-Rolle / Accessibility / TvProvider-Berechtigungen
  ui/home/         Home inklusive Watch-Next-Reihe
  ui/details/      provider-neutrale Medien-Detailseite inklusive Traileraktion
  ui/apps/         App-Übersicht
  ui/settings/     Einstellungen, Diagnose und Credits
  ui/components/   TV-Cards
  ui/theme/        TV-Material-Theme
```

Die logischen Grenzen sind so gewählt, dass spätere Gradle-Module ohne kompletten Umbau möglich bleiben. Bestehende funktionierende Bereiche werden nicht nur für eine sofortige Modularisierung oder Hilt-Migration umgebaut.

## Zielarchitektur

```text
app
core:model
core:database
core:network
core:ui
core:navigation
provider:androidtv
provider:tmdb
provider:youtube
provider:enigma2
feature:home
feature:watchnext
feature:details
feature:livetv
feature:epg
feature:apps
feature:search
feature:settings
playback
```

## Datenfluss

```text
Android TvProvider / spätere Provider
        ↓
Quellmodell + stabiler Source-Key
        ↓
MediaItem (sofort darstellbar)
        ↓
TMDB Resolver ──→ Room Mapping/Metadata Cache
        ↓                    ↑
Confidence-Prüfung      Retrofit/OkHttp
        ↓                    ↑
angereichertes MediaItem ← TMDB API
        ↓
Compose for TV UI
```

Die UI wartet nicht auf TMDB. Android-Quelldaten werden sofort dargestellt. TMDB darf anschließend Metadaten, Bilder und Trailerreferenzen ergänzen, aber weder Watch-Next-Reihenfolge noch Quellfilter oder den ursprünglichen Playback-/Deep-Link verändern.

## Android TvProvider

Für das Lesen von TV-Daten anderer Apps verwendet I Launcher `android.permission.READ_TV_LISTINGS`. Diese Berechtigung ist die gemeinsame Basis für Watch Next, Preview Channels und Preview Programs.

Watch Next wird aus `TvContract.WatchNextPrograms.CONTENT_URI` gelesen. Die Query fordert `last_engagement_time_utc_millis DESC` an. Die resultierende Reihenfolge wird im Mapper nicht verändert; Quellenfilter entfernen nur Zeilen.

Androids `COLUMN_TYPE` und `COLUMN_RELEASE_DATE` werden als Resolver-Hinweise übernommen, ohne app-spezifische Sonderlogik einzuführen.

## Gemeinsames Medienmodell

`MediaItem` ist die provider-neutrale Darstellung für Filme, Serien und Episoden. Das Modell enthält unter anderem:

- Medientyp
- Titel/Originaltitel/Untertitel/Beschreibung
- Jahr, Staffel und Episode
- TMDB-/Episode-ID und externe IDs
- Poster, Backdrop, Logo und Episode Still
- Quellbild als Fallback
- Fortschritt und Dauer
- Quellprovider, Quell-ID, Package und Playback-Intent
- Resolver-Confidence
- optionale provider-neutrale `TrailerRef`

Eine Trailerreferenz besteht aus Provider und externer ID. Phase 4 verwendet zunächst `TrailerProvider.YouTube`; die UI muss dadurch keine TMDB-Video-DTOs kennen.

## Detailnavigation und Focus-Rückkehr

Die Direktstart-Regel für Watch Next bleibt erhalten:

- kurzes `OK` auf einer Watch-Next-Karte startet den vorhandenen Source-/Playback-Intent
- `KEYCODE_INFO` oder lange `OK` öffnet die provider-neutrale Detailseite
- die Detailseite bietet `Fortsetzen`/`Wiedergeben`, Traileraktion und `Zurück`
- Watch-Next- und App-LazyList-State behalten ihre Scrollposition
- die stabile `MediaSource.sourceId` dient zur expliziten Focus-Rückgabe nach Details

Dieser Focus-Restore-Pfad ist auf dem TCL mit Phase 3 real verifiziert.

## TMDB Resolver

Der Resolver verarbeitet lokal und deterministisch Titel, Jahr, Staffel und Episode. Danach werden TMDB-Kandidaten nach Titelähnlichkeit, Typ und Jahr bewertet. Nur Treffer oberhalb der konservativen Confidence-Schwelle werden übernommen. Andernfalls bleiben die Android-Quelldaten unverändert.

Für Episoden wird zuerst die Serie aufgelöst und bei bekannter Staffel/Episode anschließend der TMDB-Episode-Endpoint verwendet.

Ein gespeichertes Source-Key-Mapping wird nur wiederverwendet, wenn normalisierter Titel, Jahr, Staffel und Episode weiterhin mit der aktuellen Quelle übereinstimmen.

## Trailer-Pipeline

Phase 4 folgt der in `AGENTS.md` vorgegebenen Priorität:

```text
TMDB-Match
   ↓
Movie/TV/Episode Details + videos
   ↓
YouTube Trailer/Teaser auswählen
   ↓
YouTube-ID in Room cachen
   ↓
MediaItem.trailer
   ↓
Details → Trailer
```

Auswahlregeln für TMDB-Videos:

1. nur YouTube-Videos mit verwertbarer ID
2. `Trailer` vor `Teaser`
3. offiziell vor inoffiziell
4. deutsch vor englisch, danach sprachneutral
5. bei Episoden Episode-Trailer vor Serien-Trailer

Falls TMDB keine verwertbare YouTube-ID liefert, wird **nicht automatisch eine zusätzliche YouTube-API-Suche gestartet**. Die Detailseite bietet stattdessen `Trailer suchen`, das eine gezielte YouTube-Suche über Android `ACTION_VIEW` öffnet. Damit entstehen weder ein zusätzlicher YouTube-API-Key noch Such-Quota oder ein eigener Stream-Extractor.

Die Trailerwiedergabe selbst wird in Phase 4 ebenfalls über Android `ACTION_VIEW` an YouTube bzw. einen geeigneten Handler delegiert. Der Launcher extrahiert keine YouTube-Streams. Ein eigener Media3-Trailerplayer kann später nur ergänzt werden, wenn eine technisch und rechtlich saubere direkte Medienquelle vorhanden ist.

## TMDB Netzwerk und Secrets

TMDB wird über Retrofit/OkHttp angesprochen. Authentifizierung erfolgt per API Read Access Token im Bearer-Header.

Der Token wird nicht im Repository gespeichert. Unterstützt werden:

- Environment `IL_TMDB_READ_ACCESS_TOKEN`
- Gradle-Property `tmdbReadAccessToken`

Der signierte Development-Publisher liest `IL_TMDB_READ_ACCESS_TOKEN` ausschließlich aus GitHub Actions Secrets. Das veröffentlichte `update.json` enthält nur den Aktivierungsstatus, niemals den Secret-Wert.

## Room Cache

Room speichert getrennt:

- Source-Key → TMDB-Mapping inklusive Confidence
- negative/no-match Mappings
- Film-/Serienmetadaten
- Episodenmetadaten
- ausgewählte YouTube-Trailer-ID und Status, ob TMDB-Videos bereits geprüft wurden

Phase 4 migriert die Datenbank von Version 1 auf 2. Bestehende Phase-3-Zeilen erhalten `videoLookupComplete = false`, damit sie genau einmal für Trailer-Metadaten nachgeladen werden können. Neue oder aktualisierte Datensätze speichern auch einen legitimen „kein Trailer gefunden“-Zustand und verhindern dadurch wiederholte unnötige Videoabfragen.

Aktuelle Cache-Policy:

- Resolver-/Metadaten-Refresh nach 30 Tagen
- harte Löschung spätestens nach 180 Tagen
- Netzwerkfehler führen bei vorhandenen Daten zum Cache-Fallback

## Bilder

TMDB-Bild-URLs werden aus `/configuration` (`secure_base_url` + unterstützte Größe + Dateipfad) erzeugt und lokal gecacht.

Artwork-Priorität:

- Episode: Episode Still → Backdrop → Poster → Quellbild
- Film/Serie: Backdrop → Poster → Quellbild

Coil übernimmt das Laden in Compose. Für das TMDB-Attributionslogo ist das Coil-SVG-Modul aktiviert.

## TMDB Attribution und Diagnose

Der Bereich `Über / Credits` zeigt ein von TMDB bereitgestelltes und unverändertes Logo sowie den vorgeschriebenen Hinweis:

> This product uses the TMDB API but is not endorsed or certified by TMDB.

Diagnoseinformationen bleiben nicht-sensitiv. Tokens und vollständige private URLs werden weder angezeigt noch geloggt.

## Development-Publishing

Der aktive Phase-4-Branch veröffentlicht signierte Development-Builds über den bestehenden `downloads`-Kanal. Der Publisher verwendet eine branchbezogene GitHub-Actions-Concurrency-Gruppe mit `cancel-in-progress`, damit ältere parallele Builds keinen neueren Development-Stand überschreiben.

Build und Unit-Tests werden vor jeder Veröffentlichung ausgeführt. Trailerstart, YouTube-Fallback, Rückkehrverhalten und Datenbankmigration müssen zusätzlich auf dem realen TCL geprüft werden.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService` mit `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true`. Das TCL-/Android-13+-Restricted-Settings-Verhalten bei lokal installierten APKs bleibt ein separates Distributionsthema und blockiert die Content-Architektur nicht.

## Gigablue

Direkte OpenWebif-Integration. Keine dreamTV-/TiviMate-Abhängigkeit, sofern OpenWebif die Funktion abdeckt.

## Build-Basis

Der aktuelle stabile Android-Toolchain-Stand wird gegen offizielle Dokumentation geprüft. Versionen werden explizit gepinnt, nicht dynamisch auf `+` gesetzt.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
