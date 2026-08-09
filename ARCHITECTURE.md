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

## Aktueller Stand

Die Gradle-Struktur bleibt vorerst bewusst bei einem `app`-Modul. Die Provider-Grenzen sind paketweise getrennt:

```text
app/
  data/apps/       installierte Apps / PackageManager
  data/tv/         Android TvProvider / Watch Next / Preview Channels / Quellenpräferenzen / Medienmapping
  data/tmdb/       TMDB API / Parser / Resolver / Metadaten / Trailer-Metadaten
  data/youtube/    YouTube-Trailerreferenzen und Embed-Helfer
  data/search/     lokale globale Suche + TMDB-Suche
  data/handoff/    optionale externe Suchübergabe, z. B. CloudStream/Kodi
  data/openwebif/  Gigablue/OpenWebif Verbindung, Cache, Bouquets, Sender, EPG und Streamauflösung
  data/epg/        M3U-Metadaten, Sender-Mapping, XMLTV-Streaming, EPG-Merge und TMDB-EPG-Anreicherung
  data/database/   Room / TMDB- und EPG-Cache
  data/update/     Development-Updatekanal
  model/           gemeinsame Media- und Live-TV-Modelle
  system/          Home-Rolle / Accessibility / TvProvider-Berechtigungen
  ui/home/         Home inklusive Hero, Watch Next, Jetzt im TV und Preview Channels
  ui/details/      provider-neutrale Medien-Detailseite inklusive Traileraktion
  ui/trailer/      interne Trailerwiedergabe
  ui/livetv/       Gigablue-Einrichtung und interner Media3-Live-TV-Player mit eingebettetem Guide
  ui/epg/          wiederverwendbare Guide-UI, im aktuellen UX vom Live-TV-Player aus geöffnet
  ui/apps/         App-Übersicht
  ui/search/       lokale/TMDB-Suche und Sprachsuche
  ui/settings/     Einstellungen, Diagnose, Live-TV-Unterpunkt und Credits
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
Android TvProvider / weitere Provider
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

Preview Channels werden als eigene Content-Reihen normalisiert. Der auf dem Zielgerät beobachtete Android-Systemwert `Channels.COLUMN_BROWSABLE=0` ist keine I-Launcher-Ausblendentscheidung: I Launcher verwaltet seine Home-Reihen lokal pro Channel-ID. Program-Level-Sichtbarkeit bleibt konservativ.

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
        └── /web/stream.m3u?ref=…     → flüchtige Streamauflösung
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

## EPG-Pipeline

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
           └─ wiederverwendbarer TV-Guide
           │
           ▼
konservativer TMDB Resolver
           │
           ▼
Bilder / Episodendaten / Details
```

### M3U-Sicherheitsgrenze

Die konfigurierte EPG-M3U wird zeilenweise nur nach Metadaten ausgewertet. Wiedergabe-URLs, Plugin-URLs oder enthaltene IPTV-Streamdaten werden nicht persistiert und nicht zur Wiedergabe verwendet. Der Parser kann aus `tvg-id`, Picon- oder Streampfaden eine Enigma2-Service-Reference als **Mapping-Hinweis** extrahieren; die URL selbst wird danach verworfen.

Die Live-TV-Wiedergabe verwendet ausdrücklich **nicht** diese IPTV-URLs, sondern löst den Stream des ausgewählten Gigablue-Senders direkt über OpenWebif auf.

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

## Live-TV-Pipeline

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
Compose-TV-Overlay / integrierter EPG
```

Die Stream-Playlist selbst ist auf 64 KiB begrenzt. Der Player verwendet kurze LAN-Timeouts und erlaubt HTTP/HTTPS-Redirects. Bei einem Senderwechsel wird der alte Stream sofort gestoppt, danach wird der Zielsender neu über OpenWebif aufgelöst.

### Zapping, Overlay und TV-Focus

Zapping arbeitet ausschließlich auf der bereits von der Gigablue gelieferten Senderliste; die Reihenfolge wird nicht neu sortiert. Unterstützt werden D-Pad ↑/↓ und die TV-Tasten CH+/CH−.

Die Player-Informationen werden nach erfolgreichem Streamstart nach drei Sekunden ausgeblendet. `OK` blendet sie wieder ein; `Zurück` blendet sichtbare Infos zuerst aus und verlässt erst beim folgenden Zurück den Player. Laden, Fehler und Zapping machen die Informationsebene erneut sichtbar.

Der bestehende EPG-Guide wird über einen `EPG`-Punkt im Player geöffnet und als Overlay über der laufenden Playeransicht dargestellt. D-Pad-Ereignisse gehören währenddessen dem Guide. Zurück schließt zunächst den Guide und kehrt zum TV-Bild zurück. EPG-Suchergebnisse öffnen den Player mit dem passenden Sender und dem Guide-Kontext statt eine eigene Hauptnavigation zu benötigen.

Beim Verlassen des Players wird die Service Reference des zuletzt aus Home gestarteten Senders verwendet, um die horizontale `Jetzt im TV`-Liste zurück an die entsprechende Karte zu scrollen und den Focus explizit wiederherzustellen.

### Player UI und Diagnose

Der Player zeigt bei eingeblendeter Information:

- Sender-Picon
- Sendername
- aktuelle Sendung
- nächste Sendung
- Position im Bouquet
- Ladezustand
- sichere Fehlerkategorie ohne Stream-URL oder Zugangsdaten
- Zurück, Sender − / Sender + und EPG

Media3-Playbackfehler zeigen nur den Media3-Fehlernamen. Private Streamdaten werden weder in der UI noch in Logs ausgegeben.

## Globale Suche

Die globale Suche arbeitet Local First. Ab zwei Zeichen werden lokale Treffer auf einem Hintergrund-Dispatcher ermittelt; längere Metadaten werden erst ab drei Zeichen einbezogen. TMDB läuft getrennt und verzögert ab drei Zeichen.

Lokale Quellen:

- installierte Apps
- sichtbare Watch-Next-Einträge
- sichtbare Preview Programs
- aktueller/gecachter Gigablue/XMLTV-EPG

Bei Watch Next werden sowohl das angereicherte gemeinsame `MediaItem` als auch die unveränderten TvProvider-Quellfelder wie Show-Titel, Episodentitel und Subtitle durchsucht. Preview Programs können zusätzlich über Channel- und Quell-App-Namen gefunden werden. Damit darf TMDB-Anreicherung die Suchbarkeit der Android-Quellidentität nicht verschlechtern.

Sprachsuche delegiert an die auf dem Gerät vorhandene Android-Spracherkennungsaktivität und übernimmt das erkannte Ergebnis in dieselbe Suchpipeline.

TMDB-only-Treffer können optional an installierte Ziel-Apps übergeben werden. CloudStream wird über dessen `cloudstreamsearch`-Intent erkannt; die tatsächliche installierte Stable-/Prerelease-/Debug-Paketvariante wird dynamisch aufgelöst. Kodi verwendet seine Android-Suchschnittstelle. I Launcher baut keine Providerlogik dieser Apps nach.

## Home und Navigation

Home besteht aus einem Hero und darunter den Content-Reihen. Der Hero liegt **außerhalb** des vertikalen Reihen-Scrollcontainers und bleibt daher sichtbar, während der Benutzer durch Watch Next, `Jetzt im TV`, Preview Channels und Apps navigiert. Fokusänderungen der Karten aktualisieren nur den Hero-Inhalt, nicht die Quellreihenfolge.

Der Hero selbst ist fokussierbar. Bei Medien öffnet `OK` die vorhandene provider-neutrale Detailansicht; bei Apps wird die App geöffnet. Das Hero-Artwork bevorzugt verfügbare Backdrops/Programm-Artwork und ergänzt Logo/Picon, Titel, Beschreibung und vorhandene Zusatzinformationen.

Die primäre Navigation ist bewusst klein: `Home · Suche · Apps · Einstellungen`. Der aktive Punkt wird nur durch einen Rahmen markiert. Auf Home darf die Navigationszeile verschwinden, sobald der Benutzer die vertikalen Content-Reihen nach unten scrollt. Live-TV-/Gigablue-Konfiguration liegt unter Einstellungen; EPG ist eine Player-Funktion und kein eigener Hauptpunkt.

## Detailnavigation und Trailer

Kurzes `OK` auf Watch Next startet weiterhin den vorhandenen Source-/Playback-Intent. `INFO` oder lange `OK` öffnet Details. Hero-Medien öffnen ebenfalls die provider-neutrale Detailansicht.

Trailer werden bevorzugt aus TMDB-Video-Metadaten aufgelöst. Wenn eine konkrete YouTube-ID vorhanden ist, startet der aktuelle UI-Polish eine eigene interne Trailer-Activity mit WebView und `WebChromeClient`-Fullscreen-Custom-View-Unterstützung, damit die Videooberfläche nicht von der Compose-Hostansicht verschluckt wird. Es findet keine Stream-Extraktion statt. Wenn keine konkrete Video-ID vorhanden ist, bleibt die externe YouTube-Suche der Fallback.

Die interne Trailer-Activity ist eine UX-Erweiterung gegenüber dem bereits hardwarebestätigten externen Trailerstart und bleibt bis zum aktuellen Gerätetest als noch nicht hardwarebestätigt gekennzeichnet.

## Room Cache und Local First

Room-Version 3 enthält `epg_channel_mappings` und `epg_programs`; Migration `2 → 3` erhält den bestehenden TMDB-Cache. Der OpenWebif-Snapshot liegt lokal in SharedPreferences. XMLTV-Programme und Sender-Mappings liegen in Room. Die bestätigten Phase-5/6-Daten können dadurch beim Start vor einem Netzwerkrefresh dargestellt werden.

Live-TV-Streamadressen werden bewusst **nicht** gecacht: Sie können Session-Informationen enthalten und werden pro Senderstart/-wechsel frisch über OpenWebif aufgelöst.

## Development-Publishing

Signierte Development-Builds laufen über den `downloads`-Kanal. Vor einer Test-APK laufen `testDebugUnitTest` und `assembleDebug`. Der aktuelle UI-Polish-Code wurde als `0.1.0-dev.115` automatisiert gebaut und veröffentlicht. Trailer-Video, Sprachsuche, neue Hero-/Navigation und eingebetteter EPG benötigen zusätzlich reale TV-Hardwarevalidierung.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService` mit `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true`. Das TCL-/Android-13+-Restricted-Settings-Verhalten bei lokal installierten APKs bleibt ein separates Distributionsthema.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
