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

`LiveTvChannel` bleibt provider-neutral und enthält Service Reference, Sendername, Picon sowie aktuelle/nächste Sendung. `LiveTvProgram` enthält optionale XMLTV-/TMDB-Metadaten wie Untertitel, Kategorien, Staffel/Episode, Erscheinungsjahr und Artwork.

## OpenWebif / Gigablue Provider

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

`OpenWebifRepository` ist die Provider-Grenze. Receiver-Adresse, Benutzername und Passwort werden ausschließlich lokal gespeichert. Passwort, vollständige private Receiver-URL, Stream-URLs und temporäre Auth-Daten werden weder geloggt noch persistiert.

## EPG-Pipeline

Die externe M3U/XMLTV-Quelle wird **nur als Metadatenquelle** verwendet. Gigablue/OpenWebif bleibt für Senderidentität, Reihenfolge und Wiedergabe maßgeblich. XMLTV liefert Guide-Metadaten und Room-Cache, TMDB ergänzt konservativ Bilder und Medieninformationen.

Der wiederverwendbare `EpgScreen` ist weiterhin eine eigene UI-Komponente, wird im aktuellen UX aber aus dem laufenden Live-TV-Player als Guide geöffnet. EPG-Suchergebnisse öffnen den passenden Sender im Player und den Guide-Kontext statt einen eigenen Hauptnavigationspunkt zu verwenden.

## Live-TV-Pipeline

```text
LiveTvChannel
   │ serviceReference
   ▼
OpenWebifRepository.resolveStream()
   │
   ├─ GET /web/stream.m3u?ref=…
   ▼
OpenWebifStreamResolver
   ├─ HTTP/HTTPS only
   ├─ URL-Userinfo entfernen
   ├─ temporäre Auth nur im RAM
   └─ keine privaten Streamdaten loggen
   ▼
OpenWebifResolvedStream
   ├─ MPEG-TS → Media3 ProgressiveMediaSource
   └─ HLS      → Media3 HlsMediaSource
   ▼
ExoPlayer / PlayerView
   +
Compose-TV-Overlay / integrierter EPG
```

Die Player-Informationen werden nach erfolgreichem Streamstart nach drei Sekunden ausgeblendet. `OK` blendet sie wieder ein; `Zurück` blendet sichtbare Infos zuerst aus und verlässt erst beim folgenden Zurück den Player. Laden, Fehler und Zapping machen die Informationsebene erneut sichtbar.

Der Guide wird über `EPG` im Player geöffnet. Währenddessen gehören D-Pad-Ereignisse dem Guide; Zurück führt wieder zum TV-Bild. Die Gigablue-Senderreihenfolge wird auch beim Zapping nicht neu sortiert.

## Globale Suche

Die globale Suche arbeitet Local First. Ab zwei Zeichen werden lokale Treffer auf einem Hintergrund-Dispatcher ermittelt; längere Metadaten werden erst ab drei Zeichen einbezogen. TMDB läuft getrennt und verzögert ab drei Zeichen.

Lokale Quellen:

- installierte Apps
- sichtbare Watch-Next-Einträge
- sichtbare Preview Programs
- aktueller/gecachter Gigablue/XMLTV-EPG

Bei Watch Next werden sowohl das angereicherte gemeinsame `MediaItem` als auch die unveränderten TvProvider-Quellfelder wie Show-Titel, Episodentitel und Subtitle durchsucht. Preview Programs können zusätzlich über Channel- und Quell-App-Namen gefunden werden.

Sprachsuche delegiert an die auf dem Gerät vorhandene Android-Spracherkennungsaktivität und übernimmt das erkannte Ergebnis in dieselbe Suchpipeline.

TMDB-only-Treffer können optional an installierte Ziel-Apps übergeben werden. CloudStream wird über dessen `cloudstreamsearch`-Intent erkannt; die tatsächlich installierte Stable-/Prerelease-/Debug-Paketvariante wird dynamisch aufgelöst. Kodi verwendet seine Android-Suchschnittstelle. I Launcher baut keine Providerlogik dieser Apps nach.

## Home und Navigation

Home besteht aus einem Hero und darunter den Content-Reihen. Der Hero liegt **außerhalb** des vertikalen Reihen-Scrollcontainers und bleibt daher sichtbar, während der Benutzer durch Watch Next, `Jetzt im TV`, Preview Channels und Apps navigiert. Fokusänderungen der Karten aktualisieren nur den Hero-Inhalt, nicht die Quellreihenfolge.

Der Hero selbst ist fokussierbar. Bei Medien öffnet `OK` die vorhandene provider-neutrale Detailansicht; bei Apps wird die App geöffnet. Das Hero-Artwork bevorzugt verfügbare Backdrops/Programm-Artwork und ergänzt Logo/Picon, Titel, Beschreibung und vorhandene Zusatzinformationen.

Die primäre Navigation ist bewusst klein: `Home · Suche · Apps · Einstellungen`. Der aktive Punkt wird nur durch einen Rahmen markiert. Auf Home darf die Navigationszeile verschwinden, sobald der Benutzer die vertikalen Content-Reihen nach unten scrollt. Live-TV-/Gigablue-Konfiguration liegt unter Einstellungen; EPG ist eine Player-Funktion und kein eigener Hauptpunkt.

## Detailnavigation und Trailer

Kurzes `OK` auf Watch Next startet weiterhin den vorhandenen Source-/Playback-Intent. `INFO` oder lange `OK` öffnet Details. Hero-Medien öffnen ebenfalls die provider-neutrale Detailansicht.

Trailer werden bevorzugt aus TMDB-Video-Metadaten aufgelöst. Wenn eine konkrete YouTube-ID vorhanden ist, startet der aktuelle UI-Polish eine eigene interne Trailer-Activity mit WebView und `WebChromeClient`-Fullscreen-Custom-View-Unterstützung. Es findet keine Stream-Extraktion statt. Wenn keine konkrete Video-ID vorhanden ist, bleibt die externe YouTube-Suche der Fallback.

Die interne Trailer-Activity ist gegenüber dem früher hardwarebestätigten externen Trailerstart noch nicht erneut auf TV-Hardware bestätigt.

## Room Cache und Local First

Room-Version 3 enthält `epg_channel_mappings` und `epg_programs`; Migration `2 → 3` erhält den bestehenden TMDB-Cache. Der OpenWebif-Snapshot liegt lokal in SharedPreferences. XMLTV-Programme und Sender-Mappings liegen in Room.

Live-TV-Streamadressen werden bewusst **nicht** gecacht.

## Development-Publishing

Signierte Development-Builds laufen über den `downloads`-Kanal. Vor einer Test-APK laufen `testDebugUnitTest` und `assembleDebug`. Der aktuelle UI-Polish-Code wurde als **`0.1.0-dev.115` (`26000115`)** automatisiert gebaut und veröffentlicht. Trailer-Video, Sprachsuche, neue Hero-/Navigation und eingebetteter EPG benötigen zusätzlich reale TV-Hardwarevalidierung.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService` mit `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true`. Das TCL-/Android-13+-Restricted-Settings-Verhalten bei lokal installierten APKs bleibt ein separates Distributionsthema.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
