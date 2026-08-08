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

## Aktueller Stand: Phase 3

Die Gradle-Struktur bleibt vorerst bewusst bei einem `app`-Modul. Die Provider-Grenzen sind bereits paketweise getrennt:

```text
app/
  data/apps/       installierte Apps / PackageManager
  data/tv/         Android TvProvider / Watch Next / Quellenpräferenzen / Medienmapping
  data/tmdb/       TMDB API / Parser / Resolver / Metadaten-Anreicherung
  data/database/   Room / TMDB-Mappings / Medien- und Episodencache
  data/update/     Development-Updatekanal
  model/           WatchNextItem + gemeinsames MediaItem/MediaSource-Modell
  system/          Home-Rolle / Accessibility / TvProvider-Berechtigungen
  ui/home/         Home inklusive Watch-Next-Reihe
  ui/details/      provider-neutrale Medien-Detailseite
  ui/apps/         App-Übersicht
  ui/settings/     Einstellungen, Diagnose und Credits
  ui/components/   TV-Cards
  ui/theme/        TV-Material-Theme
```

Die logischen Grenzen sind so gewählt, dass spätere Gradle-Module ohne kompletten Umbau möglich bleiben. Bestehende funktionierende Phase-1/2-Klassen werden nicht nur für eine sofortige Hilt-Migration umgebaut; Dependency Injection kann an den bereits getrennten Provider-/Repository-Grenzen später ergänzt werden.

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

Wichtig: Die UI wartet nicht auf TMDB. Android-Quelldaten werden sofort dargestellt. TMDB darf anschließend Metadaten und Bilder ersetzen, aber weder Watch-Next-Reihenfolge noch Quellfilter oder den ursprünglichen Playback-/Deep-Link verändern.

## Android TvProvider

Für das Lesen von TV-Daten anderer Apps verwendet I Launcher `android.permission.READ_TV_LISTINGS`. Diese Berechtigung ist die gemeinsame Basis für Watch Next, Preview Channels und Preview Programs.

Watch Next wird aus `TvContract.WatchNextPrograms.CONTENT_URI` gelesen. Die Query fordert `last_engagement_time_utc_millis DESC` an. Die resultierende Reihenfolge wird im Mapper nicht verändert; Quellenfilter entfernen nur Zeilen.

Für Phase 3 werden zusätzlich Androids `COLUMN_TYPE` und `COLUMN_RELEASE_DATE` übernommen. Sie liefern dem TMDB-Resolver einen Medientyp- und Jahres-Hinweis, ohne app-spezifische Sonderlogik einzuführen.

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

Die Quellinformation bleibt auch nach TMDB-Anreicherung erhalten.

## Detailnavigation und Focus-Rückkehr

Die verbindliche Direktstart-Regel für Watch Next bleibt erhalten:

- kurzes `OK` auf einer Watch-Next-Karte startet weiterhin direkt den vorhandenen Source-/Playback-Intent
- `KEYCODE_INFO` oder lange `OK` öffnet die provider-neutrale Detailseite
- die Detailseite bietet `Fortsetzen`/`Wiedergeben` über denselben Source-Intent und `Zurück`
- die Watch-Next- und App-LazyList-States leben oberhalb der Detailansicht und behalten ihre Scrollposition

Der reale TCL-Test zeigte, dass der LazyList-State allein nicht ausreicht: während Details sichtbar ist, wird der Home-Subtree aus der Composition entfernt und damit auch der tatsächliche Focus-Owner. Die Rückkehr speichert deshalb zusätzlich die stabile `MediaSource.sourceId` der Karte. Nach dem Schließen von Details wird die Zielposition über `LazyListState.scrollToItem()` wieder zusammengesetzt; im folgenden Compose-Frame fordert ein an genau dieser Karte befestigter `FocusRequester` den Focus zurück. Damit werden Scrollposition und Focus-Ziel getrennt und deterministisch behandelt.

Der abschließende Hardware-Retest dieses expliziten Focus-Restore-Pfads bleibt erforderlich.

## TMDB Resolver

Der Resolver verarbeitet zunächst lokal und deterministisch Titel, Jahr, Staffel und Episode. Danach werden TMDB-Kandidaten nach Titelähnlichkeit, Typ und Jahr bewertet. Nur Treffer oberhalb der konservativen Confidence-Schwelle werden übernommen. Andernfalls bleiben die Android-Quelldaten unverändert.

Für Episoden wird zuerst die Serie aufgelöst und bei bekannter Staffel/Episode anschließend der TMDB-Episode-Endpoint verwendet.

Ein gespeichertes Source-Key-Mapping wird nur wiederverwendet, wenn normalisierter Titel, Jahr, Staffel und Episode weiterhin mit der aktuellen Quelle übereinstimmen. Dadurch kann eine vom TvProvider später wiederverwendete Zeilen-ID nicht versehentlich alte TMDB-Metadaten übernehmen.

## TMDB Netzwerk und Secrets

TMDB wird über Retrofit/OkHttp angesprochen. Authentifizierung erfolgt per API Read Access Token im Bearer-Header.

Der Token wird nicht im Repository gespeichert. Unterstützt werden:

- Environment `IL_TMDB_READ_ACCESS_TOKEN`
- Gradle-Property `tmdbReadAccessToken`

Der signierte Development-Publisher liest `IL_TMDB_READ_ACCESS_TOKEN` ausschließlich aus GitHub Actions Secrets und reicht ihn als Build-Environment an Gradle weiter. Das veröffentlichte `update.json` enthält nur `tmdbConfigured=true/false`, niemals den Secret-Wert.

Seit der Live-Aktivierung von Phase 3 ist das Secret für den Development-Publisher verpflichtend: fehlt es, bricht der Workflow vor Build/Veröffentlichung ab. Dadurch kann nicht versehentlich wieder ein source-only APK als aktueller Phase-3-Build veröffentlicht werden.

Verifizierter aktiver Build: `0.1.0-dev.40` (`26000040`), `tmdbConfigured=true`.

## Room Cache

Room speichert getrennt:

- Source-Key → TMDB-Mapping inklusive Confidence
- negative/no-match Mappings
- Film-/Serienmetadaten
- Episodenmetadaten

Aktuelle Cache-Policy:

- Resolver-/Metadaten-Refresh nach 30 Tagen
- harte Löschung spätestens nach 180 Tagen
- Netzwerkfehler führen bei vorhandenen Daten zum Cache-Fallback

Damit werden wiederholte Suchen vermieden und der Launcher bleibt Local First.

## Bilder

TMDB-Bild-URLs werden aus `/configuration` (`secure_base_url` + unterstützte Größe + Dateipfad) erzeugt. Die Bildkonfiguration wird lokal gecacht.

Artwork-Priorität:

- Episode: Episode Still → Backdrop → Poster → Quellbild
- Film/Serie: Backdrop → Poster → Quellbild

Coil übernimmt das Laden in Compose. Für das von TMDB bereitgestellte SVG-Attributionslogo ist das Coil-SVG-Modul aktiviert.

## TMDB Attribution und Diagnose

Der Bereich `Über / Credits` zeigt ein von TMDB bereitgestelltes und unverändertes Logo sowie den vorgeschriebenen Hinweis:

> This product uses the TMDB API but is not endorsed or certified by TMDB.

Der TMDB-Diagnosebereich zeigt nur nicht-sensitive Informationen:

- ob der aktuelle Build TMDB aktiviert hat
- Anzahl aktuell aufgelöster Watch-Next-Einträge
- TMDB-ID
- Medientyp
- optionale Episode-ID
- Resolver-Confidence

Tokens und vollständige private URLs werden weder angezeigt noch geloggt.

## Development-Publishing

Der aktive Phase-3-Branch veröffentlicht signierte Development-Builds über den bestehenden `downloads`-Kanal. Der Publisher verwendet eine branchbezogene GitHub-Actions-Concurrency-Gruppe mit `cancel-in-progress`, damit bei mehreren schnellen Commits nie ein älterer Lauf nach einem neueren APK-Stand veröffentlicht werden kann.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService` mit `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true`. Das TCL-/Android-13+-Restricted-Settings-Verhalten bei lokal installierten APKs bleibt ein separates Distributionsthema und blockiert die Content-Architektur nicht.

## Gigablue

Direkte OpenWebif-Integration. Keine dreamTV-/TiviMate-Abhängigkeit, sofern OpenWebif die Funktion abdeckt.

## Build-Basis

Der aktuelle stabile Android-Toolchain-Stand wird gegen offizielle Dokumentation geprüft. Versionen werden explizit gepinnt, nicht dynamisch auf `+` gesetzt.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
