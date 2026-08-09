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

## Aktueller Stand: Phase 5 in Gerätetest

Die Gradle-Struktur bleibt vorerst bewusst bei einem `app`-Modul. Die Provider-Grenzen sind paketweise getrennt:

```text
app/
  data/apps/       installierte Apps / PackageManager
  data/tv/         Android TvProvider / Watch Next / Quellenpräferenzen / Medienmapping
  data/tmdb/       TMDB API / Parser / Resolver / Metadaten / Trailer-Metadaten
  data/youtube/    YouTube-Wiedergabe und Such-Fallback über Android-Intents
  data/openwebif/  Gigablue/OpenWebif Verbindung, Cache, Bouquets, Sender und Now/Next
  data/database/   Room / TMDB-Mappings / Medien-, Episoden- und Trailer-Cache
  data/update/     Development-Updatekanal
  model/           gemeinsame Media- und Live-TV-Modelle
  system/          Home-Rolle / Accessibility / TvProvider-Berechtigungen
  ui/home/         Home inklusive Watch Next und Jetzt im TV
  ui/details/      provider-neutrale Medien-Detailseite inklusive Traileraktion
  ui/livetv/       Gigablue-Einrichtung, Bouquetwahl und Live-TV-Diagnose
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

## Content-Datenfluss

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

## Gemeinsames Medienmodell

`MediaItem` ist die provider-neutrale Darstellung für Filme, Serien und Episoden. Die Quellinformation bleibt auch nach TMDB-Anreicherung erhalten. Trailer sind über eine provider-neutrale `TrailerRef` angebunden.

Für Phase 5 kommen provider-neutrale Live-TV-Modelle hinzu:

- `LiveTvChannel`: Service Reference, Sendername, Picon, aktuelle und nächste Sendung
- `LiveTvProgram`: Event-ID, Titel/Beschreibung, Start und Dauer
- Fortschritt wird aus Start, Dauer und aktueller Zeit abgeleitet

Die Home-UI kennt damit keine OpenWebif-DTOs.

## Detailnavigation und Trailer

Kurzes `OK` auf Watch Next startet den vorhandenen Source-/Playback-Intent. `INFO` oder lange `OK` öffnet Details. Trailer werden bevorzugt aus TMDB-Video-Metadaten aufgelöst und an YouTube delegiert; bei fehlender ID gibt es einen gezielten YouTube-Suchfallback. Focus-Rückgabe nach Details und Rückkehr aus YouTube sind auf TCL bestätigt.

## OpenWebif / Gigablue Provider

Phase 5 kommuniziert direkt mit der Gigablue X3 über die offizielle OpenWebif-JSON-Schnittstelle:

```text
lokale Receiver-Konfiguration
        ↓
OpenWebifRepository
        ├── /api/getservices          → Bouquets
        ├── /api/getservices?sRef=…   → Sender + Picons
        └── /api/epgnownext?bRef=…    → aktuelle/nächste Sendung
        ↓
OpenWebifMapper
        ↓
LiveTvChannel / LiveTvProgram
        ↓
Home: Jetzt im TV + Live-TV-Diagnose
```

`OpenWebifRepository` ist die Provider-Grenze. Retrofit/OkHttp-Details und OpenWebif-Feldnamen bleiben unter `data/openwebif`.

### Verbindung und Authentifizierung

Die Receiver-Adresse wird lokal eingegeben und deterministisch normalisiert. Ohne Schema wird `http://` verwendet; HTTP und HTTPS sind erlaubt. Eingebettete Zugangsdaten in URLs werden abgelehnt. Optional wird HTTP Basic Authentication über einen Authorization-Header verwendet.

Normale OpenWebif-LAN-Installationen verwenden häufig unverschlüsseltes HTTP. Deshalb erlaubt die App Cleartext-Traffic. Die UI weist darauf hin, Zugangsdaten über HTTP nur im vertrauenswürdigen Heimnetz zu verwenden.

Receiver-Adresse, Benutzername und Passwort werden ausschließlich lokal gespeichert. Passwort und vollständige private Receiver-URL werden weder geloggt noch in Diagnosemeldungen ausgegeben. `android:allowBackup=false` verhindert, dass diese lokalen Zugangsdaten über Android Auto Backup ausgelagert werden.

### Bouquets und Sender

Ein `getservices`-Aufruf ohne `sRef` liefert die TV-Bouquets. Das ausgewählte Bouquet wird lokal gespeichert. Ein zweiter `getservices`-Aufruf mit der Bouquet-Service-Reference liefert Sender in Receiver-Reihenfolge. OpenWebif-Marker mit `pos=0` werden aus der Content-Reihe entfernt, echte Sender bleiben in Quellreihenfolge erhalten.

Picons werden als relative OpenWebif-Pfade geliefert und gegen die konfigurierte Receiver-Basisadresse aufgelöst.

### EPG Now/Next

`/api/epgnownext` liefert die aktuellen und folgenden Events des Bouquets. Die Zuordnung erfolgt ausschließlich über die Enigma2-Service-Reference. Aktuelle Sendung wird anhand des Epoch-Zeitfensters ermittelt; der nächste spätere Event wird als `next` übernommen.

### Local First und Aktualisierung

Der letzte erfolgreich geladene Snapshot aus Bouquet, ausgewähltem Bouquet, Sendern und Now/Next wird lokal gecacht. Beim App-Start kann `Jetzt im TV` deshalb sofort aus dem letzten Snapshot erscheinen. Anschließend aktualisiert der Repository-Pfad im Hintergrund; während der Launcher sichtbar bleibt wird alle fünf Minuten erneut aktualisiert.

Ein Netzwerkfehler überschreibt vorhandene lokale Sender/EPG-Daten nicht. Fehler werden nur als sanitisierte Zustände wie HTTP-Code, Timeout, DNS- oder Verbindungsfehler dargestellt.

### Scope-Grenze Phase 5

Die `Jetzt im TV`-Karten sind noch keine Player-Einstiegspunkte. Auswahl öffnet die Live-TV-Ansicht. Interne Stream-URLs, Media3-Wiedergabe und Zapping folgen bewusst erst in Phase 7.

## TMDB Resolver und Cache

Der Resolver verarbeitet lokal und deterministisch Titel, Jahr, Staffel und Episode und übernimmt nur Treffer oberhalb der konservativen Confidence-Schwelle. Room speichert Source-Mappings, negative Treffer, Film-/Serienmetadaten, Episodenmetadaten und Trailer-IDs. Cache-Refresh erfolgt nach 30 Tagen, harte Löschung spätestens nach 180 Tagen.

## Bilder

TMDB-Bild-URLs werden aus `/configuration` erzeugt und lokal gecacht. Artwork-Priorität: Episode Still → Backdrop → Poster → Quellbild; Film/Serie: Backdrop → Poster → Quellbild. OpenWebif-Picons bleiben eine separate lokale Senderbildquelle.

## TMDB Attribution und Diagnose

Der Bereich `Über / Credits` zeigt das TMDB-Logo und den vorgeschriebenen Hinweis. Tokens und vollständige private URLs werden weder angezeigt noch geloggt.

## Development-Publishing

Signierte Development-Builds laufen über den `downloads`-Kanal. Build und Unit-Tests werden vor jeder Veröffentlichung ausgeführt. Phase 5 wird über `agent/phase-5-openwebif` veröffentlicht und gilt erst nach realem TCL-/Gigablue-Test als abgeschlossen.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService` mit `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true`. Das TCL-/Android-13+-Restricted-Settings-Verhalten bei lokal installierten APKs bleibt ein separates Distributionsthema.

## Build-Basis

Der aktuelle stabile Android-Toolchain-Stand wird gegen offizielle Dokumentation geprüft. Versionen werden explizit gepinnt, nicht dynamisch auf `+` gesetzt.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
