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
  ui/apps/         App-Übersicht
  ui/settings/     Einstellungen und Diagnose
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

## TMDB Resolver

Der Resolver verarbeitet zunächst lokal und deterministisch Titel, Jahr, Staffel und Episode. Danach werden TMDB-Kandidaten nach Titelähnlichkeit, Typ und Jahr bewertet. Nur Treffer oberhalb der konservativen Confidence-Schwelle werden übernommen. Andernfalls bleiben die Android-Quelldaten unverändert.

Für Episoden wird zuerst die Serie aufgelöst und bei bekannter Staffel/Episode anschließend der TMDB-Episode-Endpoint verwendet.

## TMDB Netzwerk und Secrets

TMDB wird über Retrofit/OkHttp angesprochen. Authentifizierung erfolgt per API Read Access Token im Bearer-Header.

Der Token wird nicht im Repository gespeichert. Unterstützt werden:

- Environment `IL_TMDB_READ_ACCESS_TOKEN`
- Gradle-Property `tmdbReadAccessToken`

Ohne Token bleibt der TMDB-Provider deaktiviert und I Launcher arbeitet vollständig mit Quelldaten weiter.

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

Coil übernimmt das Laden in Compose.

## TMDB Attribution

Vor Aktivierung der Live-TMDB-Nutzung im Development-/Release-Build wird ein About/Credits-Bereich mit einem freigegebenen TMDB-Logo und dem von TMDB geforderten Hinweis ergänzt. Logo/Marke werden nicht verändert oder als I-Launcher-Branding dargestellt.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService` mit `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true`. Das TCL-/Android-13+-Restricted-Settings-Verhalten bei lokal installierten APKs bleibt ein separates Distributionsthema und blockiert die Content-Architektur nicht.

## Gigablue

Direkte OpenWebif-Integration. Keine dreamTV-/TiviMate-Abhängigkeit, sofern OpenWebif die Funktion abdeckt.

## Build-Basis

Der aktuelle stabile Android-Toolchain-Stand wird gegen offizielle Dokumentation geprüft. Versionen werden explizit gepinnt, nicht dynamisch auf `+` gesetzt.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
