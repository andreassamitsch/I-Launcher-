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

## Aktueller Stand: Phase 4 abgeschlossen

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

`MediaItem` ist die provider-neutrale Darstellung für Filme, Serien und Episoden. Das Modell enthält unter anderem Medientyp, Titel, Beschreibung, Jahr, Staffel/Episode, TMDB-/Episode-ID, Bilder, Fortschritt, Quellprovider, Playback-Intent, Resolver-Confidence und eine optionale provider-neutrale `TrailerRef`.

Eine Trailerreferenz besteht aus Provider und externer ID. Phase 4 verwendet `TrailerProvider.YouTube`; die UI kennt keine TMDB-Video-DTOs.

## Detailnavigation und Focus-Rückkehr

- kurzes `OK` auf einer Watch-Next-Karte startet den vorhandenen Source-/Playback-Intent
- `KEYCODE_INFO` oder lange `OK` öffnet die provider-neutrale Detailseite
- die Detailseite bietet `Fortsetzen`/`Wiedergeben`, Traileraktion und `Zurück`
- Watch-Next- und App-LazyList-State behalten ihre Scrollposition
- die stabile `MediaSource.sourceId` dient zur expliziten Focus-Rückgabe nach Details

Dieser Pfad ist auf dem TCL real verifiziert, einschließlich Rückkehr aus externer YouTube-Wiedergabe.

## TMDB Resolver

Der Resolver verarbeitet lokal und deterministisch Titel, Jahr, Staffel und Episode. Danach werden TMDB-Kandidaten nach Titelähnlichkeit, Typ und Jahr bewertet. Nur Treffer oberhalb der konservativen Confidence-Schwelle werden übernommen. Andernfalls bleiben die Android-Quelldaten unverändert.

Für Episoden wird zuerst die Serie aufgelöst und bei bekannter Staffel/Episode anschließend der TMDB-Episode-Endpoint verwendet. Ein gespeichertes Source-Key-Mapping wird nur wiederverwendet, wenn normalisierter Titel, Jahr, Staffel und Episode weiterhin mit der aktuellen Quelle übereinstimmen.

## Trailer-Pipeline

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

Auswahlregeln: nur YouTube-Videos mit ID; Trailer vor Teaser; offiziell vor inoffiziell; deutsch vor englisch, danach sprachneutral; bei Episoden Episode-Trailer vor Serien-Trailer.

Falls TMDB keine verwertbare YouTube-ID liefert, wird keine zusätzliche YouTube-Data-API-Suche gestartet. Die Detailseite bietet `Trailer suchen`, das eine gezielte YouTube-Suche über Android `ACTION_VIEW` öffnet. Die Trailerwiedergabe wird ebenfalls an YouTube bzw. einen geeigneten Handler delegiert; der Launcher extrahiert keine YouTube-Streams.

## TMDB Netzwerk und Secrets

TMDB wird über Retrofit/OkHttp mit Bearer-Token angesprochen. Der Token wird nicht im Repository gespeichert. Der signierte Development-Publisher liest `IL_TMDB_READ_ACCESS_TOKEN` ausschließlich aus GitHub Actions Secrets.

## Room Cache

Room speichert Source-Key-Mappings, negative Treffer, Film-/Serienmetadaten, Episodenmetadaten sowie ausgewählte YouTube-Trailer-ID und Lookup-Status. Phase 4 migriert die Datenbank von Version 1 auf 2. Der reale Update-Test `dev.45` → `dev.47` bestätigte die Migration ohne Cache-Verlust.

Cache-Policy: Resolver-/Metadaten-Refresh nach 30 Tagen, harte Löschung spätestens nach 180 Tagen, Netzwerkfehler nutzen vorhandene Cache-Daten.

## Bilder

TMDB-Bild-URLs werden aus `/configuration` erzeugt und lokal gecacht. Artwork-Priorität: Episode Still → Backdrop → Poster → Quellbild; Film/Serie: Backdrop → Poster → Quellbild.

## TMDB Attribution und Diagnose

Der Bereich `Über / Credits` zeigt das TMDB-Logo und den vorgeschriebenen Hinweis. Tokens und vollständige private URLs werden weder angezeigt noch geloggt.

## Development-Publishing

Signierte Development-Builds laufen über den `downloads`-Kanal. Build und Unit-Tests werden vor jeder Veröffentlichung ausgeführt. Der bestätigte Phase-4-Build ist `0.1.0-dev.47` (`26000047`).

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService` mit `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true`. Das TCL-/Android-13+-Restricted-Settings-Verhalten bei lokal installierten APKs bleibt ein separates Distributionsthema.

## Gigablue / nächste Phase

Phase 5 integriert die Gigablue X3 direkt über Enigma2/OpenWebif. Verbindung, optionale HTTP-Authentifizierung, Bouquets, Sender und EPG Now/Next werden hinter einer eigenen Provider-/Repository-Grenze implementiert. Zugangsdaten bleiben lokal und dürfen weder in Logs noch im Repository landen. Die Home-UI erhält daraus eine Reihe `Jetzt im TV`; vollständiger EPG-Guide und interner Live-TV-Player folgen erst in Phase 6/7.

## Build-Basis

Der aktuelle stabile Android-Toolchain-Stand wird gegen offizielle Dokumentation geprüft. Versionen werden explizit gepinnt, nicht dynamisch auf `+` gesetzt.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
