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
- Gigablue bleibt für Live-TV die Identitäts-/Reihenfolge-/Streamquelle; externe EPG-Quellen ergänzen nur Metadaten

## Aktueller Stand: Phase 6 in Entwicklung

Die Gradle-Struktur bleibt vorerst bewusst bei einem `app`-Modul. Die Provider-Grenzen sind paketweise getrennt:

```text
app/
  data/apps/       installierte Apps / PackageManager
  data/tv/         Android TvProvider / Watch Next / Quellenpräferenzen / Medienmapping
  data/tmdb/       TMDB API / Parser / Resolver / Metadaten / Trailer-Metadaten
  data/youtube/    YouTube-Wiedergabe und Such-Fallback über Android-Intents
  data/openwebif/  Gigablue/OpenWebif Verbindung, Cache, Bouquets, Sender und Now/Next
  data/epg/        M3U-Metadaten, Sender-Mapping, XMLTV-Streaming, EPG-Merge und TMDB-EPG-Anreicherung
  data/database/   Room / TMDB- und EPG-Cache
  data/update/     Development-Updatekanal
  model/           gemeinsame Media- und Live-TV-Modelle
  system/          Home-Rolle / Accessibility / TvProvider-Berechtigungen
  ui/home/         Home inklusive Watch Next und Jetzt im TV
  ui/details/      provider-neutrale Medien-Detailseite inklusive Traileraktion
  ui/livetv/       Gigablue-Einrichtung und Sender-/EPG-Zuordnungsdiagnose
  ui/epg/          vollständige Sender-/Programmübersicht
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

## Gemeinsame Medien- und Live-TV-Modelle

`MediaItem` ist die provider-neutrale Darstellung für Filme, Serien und Episoden. Die Quellinformation bleibt auch nach TMDB-Anreicherung erhalten. Trailer sind über eine provider-neutrale `TrailerRef` angebunden.

`LiveTvChannel` bleibt provider-neutral und enthält Service Reference, Sendername, Picon sowie aktuelle/nächste Sendung. `LiveTvProgram` wurde für Phase 6 erweitert um optionale XMLTV-/TMDB-Metadaten wie Untertitel, Kategorien, Staffel/Episode, Erscheinungsjahr und Artwork. Alte lokale Phase-5-Snapshots bleiben lesbar, weil die neuen Felder optional sind.

## OpenWebif / Gigablue Provider

Phase 5 kommuniziert direkt mit der Gigablue X3 über die OpenWebif-JSON-Schnittstelle:

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
```

`OpenWebifRepository` ist die Provider-Grenze. Retrofit/OkHttp-Details und OpenWebif-Feldnamen bleiben unter `data/openwebif`.

### Verbindung und Authentifizierung

Die Receiver-Adresse wird lokal eingegeben und deterministisch normalisiert. Ohne Schema wird `http://` verwendet; HTTP und HTTPS sind erlaubt. Eingebettete Zugangsdaten in URLs werden abgelehnt. Optional wird HTTP Basic Authentication verwendet.

Normale OpenWebif-LAN-Installationen verwenden häufig unverschlüsseltes HTTP. Deshalb erlaubt die App Cleartext-Traffic. Receiver-Adresse, Benutzername und Passwort werden ausschließlich lokal gespeichert. Passwort und vollständige private Receiver-URL werden weder geloggt noch in Diagnosemeldungen ausgegeben. `android:allowBackup=false` verhindert Android Auto Backup dieser lokalen Zugangsdaten.

### Bouquets, Sender und Now/Next

Ein `getservices`-Aufruf ohne `sRef` liefert die TV-Bouquets. Das ausgewählte Bouquet wird lokal gespeichert. Ein zweiter `getservices`-Aufruf mit der Bouquet-Service-Reference liefert Sender in Receiver-Reihenfolge. OpenWebif-Marker mit `pos=0` werden entfernt, echte Sender bleiben in Quellreihenfolge erhalten.

`/api/epgnownext` liefert aktuelle und folgende Events des Bouquets. Die Zuordnung erfolgt über die Enigma2-Service-Reference. OpenWebif-Zeitfenster/Event-IDs bleiben die bevorzugte Primärinformation für Now/Next.

## Phase-6-EPG-Pipeline

Die externe M3U/XMLTV-Quelle wird **nur als Metadatenquelle** verwendet:

```text
Gigablue / OpenWebif
  ├─ Bouquet
  ├─ Senderreihenfolge
  ├─ serviceReference
  └─ Now/Next
           │
           ▼
M3U-Metadatenquelle
  ├─ x-tvg-url
  ├─ tvg-id / tvg-id-ALT
  ├─ tvg-name
  ├─ tvg-logo
  └─ Service-Reference-Hinweise
           │
           ▼
serviceReference ↔ XMLTV channel-id
           │
           ▼
GZIP/XMLTV Streaming Parser
           │
           ▼
Room EPG Cache
           │
           ▼
OpenWebif + XMLTV Merge
           │
           ├─ Home: Jetzt im TV
           └─ EPG Guide
           │
           ▼
konservativer TMDB Resolver
           │
           ▼
Bilder / Episodendaten / Details
```

### M3U-Sicherheitsgrenze

Die konfigurierte M3U wird zeilenweise nur nach Metadaten ausgewertet. Wiedergabe-URLs, Plugin-URLs oder enthaltene IPTV-Streamdaten werden nicht persistiert und nicht zur Wiedergabe verwendet. Der Parser kann aus `tvg-id`, Picon- oder Streampfaden eine Enigma2-Service-Reference als **Mapping-Hinweis** extrahieren; die URL selbst wird danach verworfen.

Damit kann eine bestehende M3U bei der Senderzuordnung helfen, ohne dass I Launcher deren Streams übernimmt. Für Live-TV bleibt Phase 7 auf OpenWebif/Enigma2 ausgerichtet.

### Senderzuordnung

Automatisches Mapping erfolgt konservativ:

1. exakte Enigma2-Service-Reference
2. eindeutiger normalisierter Sendername
3. nur bei sehr hoher Sicherheit ein eindeutiger Fuzzy-Treffer
4. ansonsten keine automatische Zuordnung

Die Normalisierung entfernt u. a. reine HD/UHD/SD-Zusätze und kennt wenige deterministische Schreibvarianten wie `ProSieben`/`Pro7`. Unsichere Sender erscheinen in der Live-TV-Ansicht mit manueller XMLTV-Auswahl. Ein manuelles Mapping wird lokal dauerhaft gegenüber automatischen Treffern bevorzugt.

`tvg-id-ALT` wird als alternative XMLTV-Identität berücksichtigt. Wenn die primäre ID in der realen XMLTV-Datei keine Programme liefert, eine Alternate-ID aber schon, kann das Mapping auf diese tatsächlich vorhandene ID wechseln.

### XMLTV Streaming und Limits

Die XMLTV-Datei wird nicht vollständig als String in den Arbeitsspeicher geladen. Datenfluss:

```text
OkHttp Response
 → BufferedInputStream
 → GZIPInputStream bei GZIP-Magic-Bytes
 → sicher konfigurierter SAX Parser
 → nur gemappte channel-IDs
 → nur begrenztes Zeitfenster
 → Room
```

Externe XML-Entities und DTD-Nachladen sind deaktiviert. Der aktuelle Guide-Cache umfasst sechs Stunden Vergangenheit und 72 Stunden Zukunft. Die M3U wird maximal täglich neu geladen; XMLTV maximal alle sechs Stunden, außer neue Sender-IDs benötigen erstmals Daten oder der Benutzer erzwingt ein Update.

### XMLTV/OpenWebif Merge

OpenWebif bleibt bei einer übereinstimmenden Sendung maßgeblich für Event-ID, Startzeit und Dauer. XMLTV füllt zusätzliche Informationen wie Beschreibung, Untertitel, Kategorien, Staffel/Episode, Jahr und Programm-Icon. Wenn OpenWebif für einen gemappten Sender kein Now/Next liefert, darf XMLTV als Fallback die aktuellen/folgenden Programmdaten bereitstellen.

Die UI kennt weder XMLTV-Tags noch M3U-Felder, sondern ausschließlich `LiveTvChannel`/`LiveTvProgram`.

### EPG/TMDB-Anreicherung

TMDB wird nicht blind für jede TV-Sendung aufgerufen. Automatische Auflösung erfolgt nur, wenn XMLTV-Informationen einen Film-/Seriencharakter plausibel machen, z. B. über Kategorie oder Staffel/Episode. Der vorhandene konservative TMDB-Resolver entscheidet weiterhin über die Match-Confidence.

Aktuelle Programme werden progressiv in begrenzter Zahl angereichert. Weitere Guide-Einträge werden bei Auswahl aufgelöst. TMDB ergänzt Artwork und fehlende Details, überschreibt aber nicht zuverlässige Quell-EPG-Zeitdaten.

## Room Cache

Room-Version 3 ergänzt zwei Tabellen ohne Verlust des bestehenden TMDB-Caches:

- `epg_channel_mappings`: stabile Zuordnung Enigma2-Service-Reference → XMLTV-ID, inklusive Match-Methode/Confidence
- `epg_programs`: lokaler XMLTV-Guide für die gemappten Sender

Migration `2 → 3` erzeugt ausschließlich die neuen Tabellen/Indizes. Die bereits getestete Phase-4-Migration `1 → 2` bleibt erhalten.

## Local First und Aktualisierung

Der OpenWebif-Snapshot bleibt in SharedPreferences verfügbar. XMLTV-Programme und Sender-Mappings liegen in Room. Beim Start kann die Oberfläche daher bestehende lokale TV-/EPG-Daten darstellen, bevor Netzwerkupdates abgeschlossen sind. Netzwerkfehler löschen den letzten nutzbaren lokalen Stand nicht.

## Home und EPG UI

`Jetzt im TV` behält die Gigablue-Senderreihenfolge. Wenn XMLTV/TMDB ein passendes Programmbild liefert, wird dieses als Card-Artwork verwendet und das Sender-Picon darüber eingeblendet; ohne Programmbild bleibt die bisherige Picon-Karte erhalten.

Der neue Bereich `EPG` zeigt links Sender und rechts das Programm des ausgewählten Senders. Auswahl eines Programmeintrags zeigt Beschreibung, Staffel/Episode, Kategorien und verfügbares Artwork und kann die TMDB-Auflösung dieses Eintrags auslösen.

## Detailnavigation und Trailer

Kurzes `OK` auf Watch Next startet den vorhandenen Source-/Playback-Intent. `INFO` oder lange `OK` öffnet Details. Trailer werden bevorzugt aus TMDB-Video-Metadaten aufgelöst und an YouTube delegiert; bei fehlender ID gibt es einen gezielten YouTube-Suchfallback. Focus-Rückgabe nach Details und Rückkehr aus YouTube sind auf TCL bestätigt.

## TMDB Resolver und Bilder

Der Resolver verarbeitet lokal und deterministisch Titel, Jahr, Staffel und Episode und übernimmt nur Treffer oberhalb der konservativen Confidence-Schwelle. Room speichert Source-Mappings, negative Treffer, Film-/Serienmetadaten, Episodenmetadaten und Trailer-IDs.

TMDB-Artwork-Priorität bleibt: Episode Still → Backdrop → Poster → Quellbild; Film/Serie: Backdrop → Poster → Quellbild. Im Live-TV-Pfad kann ein XMLTV-Programmbild als zusätzliche Quelle dienen.

## Development-Publishing

Signierte Development-Builds laufen über den `downloads`-Kanal. Phase 6 wird über `agent/phase-6-epg` veröffentlicht. Build und Unit-Tests müssen vor jeder Test-APK erfolgreich sein. Phase 5/6 gelten erst nach den jeweils relevanten realen TCL-/Gigablue-/XMLTV-Tests als hardwareverifiziert.

## Scope-Grenze zu Phase 7

EPG-Karten und Guide-Einträge sind noch keine internen Stream-Player. Media3-Wiedergabe, Gigablue-Stream-URLs, Zapping und Player-UI folgen bewusst in Phase 7.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService` mit `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true`. Das TCL-/Android-13+-Restricted-Settings-Verhalten bei lokal installierten APKs bleibt ein separates Distributionsthema.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
