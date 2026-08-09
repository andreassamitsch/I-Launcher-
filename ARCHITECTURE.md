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
- Gigablue bleibt für Live-TV die Identitäts-, Reihenfolge- und Streamquelle; externe EPG-Quellen ergänzen nur Metadaten
- Stream-Adressen, Session-IDs und Streaming-Zugangsdaten werden nicht persistiert oder geloggt

## Aktueller Stand: Phase 7 in Entwicklung

Die Gradle-Struktur bleibt vorerst bewusst bei einem `app`-Modul. Die Provider-Grenzen sind paketweise getrennt:

```text
app/
  data/apps/       installierte Apps / PackageManager
  data/tv/         Android TvProvider / Watch Next / Quellenpräferenzen / Medienmapping
  data/tmdb/       TMDB API / Parser / Resolver / Metadaten / Trailer-Metadaten
  data/youtube/    YouTube-Wiedergabe und Such-Fallback über Android-Intents
  data/openwebif/  Gigablue/OpenWebif Verbindung, Cache, Bouquets, Sender, EPG und Streamauflösung
  data/epg/        M3U-Metadaten, Sender-Mapping, XMLTV-Streaming, EPG-Merge und TMDB-EPG-Anreicherung
  data/database/   Room / TMDB- und EPG-Cache
  data/update/     Development-Updatekanal
  model/           gemeinsame Media- und Live-TV-Modelle
  system/          Home-Rolle / Accessibility / TvProvider-Berechtigungen
  ui/home/         Home inklusive Watch Next und Jetzt im TV
  ui/details/      provider-neutrale Medien-Detailseite inklusive Traileraktion
  ui/livetv/       Gigablue-Einrichtung, EPG-Diagnose und interner Media3-Live-TV-Player
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

`LiveTvChannel` bleibt provider-neutral und enthält Service Reference, Sendername, Picon sowie aktuelle/nächste Sendung. `LiveTvProgram` enthält optionale XMLTV-/TMDB-Metadaten wie Untertitel, Kategorien, Staffel/Episode, Erscheinungsjahr und Artwork. Alte lokale Phase-5-Snapshots bleiben lesbar, weil die später ergänzten Felder optional sind.

## OpenWebif / Gigablue Provider

Phase 5 kommuniziert direkt mit der Gigablue X3 über OpenWebif:

```text
lokale Receiver-Konfiguration
        ↓
OpenWebifRepository
        ├── /api/getservices          → Bouquets
        ├── /api/getservices?sRef=…   → Sender + Picons
        ├── /api/epgnownext?bRef=…    → aktuelle/nächste Sendung
        └── /web/stream.m3u?ref=…     → flüchtige Streamauflösung für Phase 7
        ↓
LiveTvChannel / LiveTvProgram
```

`OpenWebifRepository` ist die Provider-Grenze. Retrofit-/OkHttp-Details und OpenWebif-Feldnamen bleiben unter `data/openwebif`.

### Verbindung und Authentifizierung

Die Receiver-Adresse wird lokal eingegeben und deterministisch normalisiert. Ohne Schema wird `http://` verwendet; HTTP und HTTPS sind erlaubt. Eingebettete Zugangsdaten in der konfigurierten Basis-URL werden abgelehnt. Optional wird HTTP Basic Authentication verwendet.

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

Die konfigurierte EPG-M3U wird zeilenweise nur nach Metadaten ausgewertet. Wiedergabe-URLs, Plugin-URLs oder enthaltene IPTV-Streamdaten werden nicht persistiert und nicht zur Wiedergabe verwendet. Der Parser kann aus `tvg-id`, Picon- oder Streampfaden eine Enigma2-Service-Reference als **Mapping-Hinweis** extrahieren; die URL selbst wird danach verworfen.

Phase 7 verwendet ausdrücklich **nicht** diese IPTV-URLs, sondern löst den Stream des ausgewählten Gigablue-Senders direkt über OpenWebif auf.

### Senderzuordnung

Automatisches Mapping erfolgt konservativ:

1. exakte Enigma2-Service-Reference
2. eindeutiger normalisierter Sendername
3. nur bei sehr hoher Sicherheit ein eindeutiger Fuzzy-Treffer
4. ansonsten keine automatische Zuordnung

Ein manuelles Mapping wird lokal dauerhaft gegenüber automatischen Treffern bevorzugt. `tvg-id-ALT` wird als alternative XMLTV-Identität berücksichtigt, wenn die primäre ID keine Programmdaten liefert.

### XMLTV Streaming und Limits

Die XMLTV-Datei wird nicht vollständig als String in den Arbeitsspeicher geladen:

```text
OkHttp Response
 → BufferedInputStream
 → GZIPInputStream bei GZIP-Magic-Bytes
 → sicher konfigurierter SAX Parser
 → nur gemappte channel-IDs
 → begrenztes Zeitfenster
 → Room
```

Externe XML-Entities und DTD-Nachladen sind deaktiviert. Der Guide-Cache umfasst sechs Stunden Vergangenheit und 72 Stunden Zukunft. Die M3U wird maximal täglich neu geladen; XMLTV maximal alle sechs Stunden, außer neue Sender-IDs benötigen erstmals Daten oder der Benutzer erzwingt ein Update.

### XMLTV/OpenWebif Merge

OpenWebif bleibt bei einer übereinstimmenden Sendung maßgeblich für Event-ID, Startzeit und Dauer. XMLTV füllt zusätzliche Informationen wie Beschreibung, Untertitel, Kategorien, Staffel/Episode, Jahr und Programm-Icon. Schwache zeitliche Überlappungen reichen nicht für ein Merge. Wenn OpenWebif für einen gemappten Sender kein Now/Next liefert, darf XMLTV als Fallback die aktuellen/folgenden Programmdaten bereitstellen.

### EPG/TMDB-Anreicherung

TMDB wird nicht blind für jede TV-Sendung aufgerufen. Automatische Auflösung erfolgt nur, wenn XMLTV-Informationen einen Film-/Seriencharakter plausibel machen. Aktuelle Programme werden progressiv in begrenzter Zahl angereichert; weitere Guide-Einträge bei Auswahl. TMDB überschreibt keine zuverlässigen EPG-Zeitdaten.

## Phase-7-Live-TV-Pipeline

Der interne Player startet direkt aus einer `Jetzt im TV`-Karte. Entscheidend ist, dass I Launcher den finalen Stream-Port oder die endgültige Stream-Adresse nicht selbst rät:

```text
LiveTvChannel
   │ serviceReference
   ▼
OpenWebifRepository.resolveStream()
   │
   ├─ GET /web/stream.m3u?ref=…
   │
   ▼
OpenWebifStreamResolver
   ├─ akzeptiert nur HTTP/HTTPS
   ├─ entfernt URL-Userinfo
   ├─ wandelt Stream-/Session-Auth in flüchtige HTTP-Header um
   └─ persistiert/loggt weder URL noch Header
   │
   ▼
OpenWebifResolvedStream (nur RAM)
   │
   ├─ MPEG-TS → Media3 ProgressiveMediaSource
   └─ HLS      → Media3 HlsMediaSource
   │
   ▼
ExoPlayer → PlayerView
   +
Compose-TV-Overlay
```

Die Stream-Playlist selbst ist auf 64 KiB begrenzt. Der Player verwendet kurze LAN-Timeouts und erlaubt HTTP/HTTPS-Redirects. Bei einem Senderwechsel wird der alte Stream sofort gestoppt, danach wird der Zielsender neu über OpenWebif aufgelöst.

### Zapping und TV-Focus

Zapping arbeitet ausschließlich auf der bereits von der Gigablue gelieferten Senderliste; die Reihenfolge wird nicht neu sortiert. Unterstützt werden D-Pad ↑/↓ und die TV-Tasten CH+/CH−. Die Key-Behandlung verwendet die Compose-`Key`-Abstraktion, nicht gerätespezifische Keycodes.

Beim Verlassen des Players wird die Service Reference des zuletzt aus Home gestarteten Senders verwendet, um die horizontale `Jetzt im TV`-Liste zurück an die entsprechende Karte zu scrollen und den Focus explizit wiederherzustellen. Das reale Verhalten muss auf dem TCL bestätigt werden.

### Player UI und Diagnose

Der Player zeigt als Overlay:

- Sender-Picon
- Sendername
- aktuelle Sendung
- nächste Sendung
- Position im Bouquet
- Ladezustand
- sichere Fehlerkategorie ohne Stream-URL oder Zugangsdaten
- Zurück sowie Sender − / Sender +

Media3-Playbackfehler zeigen nur den Media3-Fehlernamen. Private Streamdaten werden weder in der UI noch in Logs ausgegeben.

## Room Cache und Local First

Room-Version 3 enthält `epg_channel_mappings` und `epg_programs`; Migration `2 → 3` erhält den bestehenden TMDB-Cache. Der OpenWebif-Snapshot liegt lokal in SharedPreferences. XMLTV-Programme und Sender-Mappings liegen in Room. Die bestätigten Phase-5/6-Daten können dadurch beim Start vor einem Netzwerkrefresh dargestellt werden.

Live-TV-Streamadressen werden bewusst **nicht** gecacht: Sie können Session-Informationen enthalten und werden pro Senderstart/-wechsel frisch über OpenWebif aufgelöst.

## Home und EPG UI

`Jetzt im TV` behält die Gigablue-Senderreihenfolge. Wenn XMLTV/TMDB ein passendes Programmbild liefert, wird dieses als Card-Artwork verwendet und das Sender-Picon darüber eingeblendet; ohne Programmbild bleibt die Picon-Karte erhalten. In Phase 7 startet `OK` auf der Karte den internen Media3-Player.

Der Bereich `EPG` zeigt links Sender und rechts das Programm des ausgewählten Senders. Auswahl eines Programmeintrags zeigt Beschreibung, Staffel/Episode, Kategorien und verfügbares Artwork und kann die TMDB-Auflösung dieses Eintrags auslösen.

## Detailnavigation und Trailer

Kurzes `OK` auf Watch Next startet den vorhandenen Source-/Playback-Intent. `INFO` oder lange `OK` öffnet Details. Trailer werden bevorzugt aus TMDB-Video-Metadaten aufgelöst und an YouTube delegiert; bei fehlender ID gibt es einen gezielten YouTube-Suchfallback. Focus-Rückgabe nach Details und Rückkehr aus YouTube sind auf TCL bestätigt.

## Development-Publishing

Signierte Development-Builds laufen über den `downloads`-Kanal. Phase 7 veröffentlicht aus `agent/phase-7-live-tv`. Vor einer Test-APK laufen `testDebugUnitTest` und `assembleDebug`. Phasen 1 bis 6 sind hardwarebestätigt; Phase 7 gilt erst nach dem realen TCL-/Gigablue-Streamingtest als hardwareverifiziert.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService` mit `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true`. Das TCL-/Android-13+-Restricted-Settings-Verhalten bei lokal installierten APKs bleibt ein separates Distributionsthema.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
