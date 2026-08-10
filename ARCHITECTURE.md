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
  ui/details/      provider-neutrale Medien-Detailseite inklusive Trailer-/Suchaktionen
  ui/trailer/      interne Trailerwiedergabe
  ui/livetv/       Gigablue-Einrichtung und interner Media3-Live-TV-Player mit eingebettetem Guide
  ui/epg/          wiederverwendbare Guide-UI, im aktuellen UX vom Live-TV-Player aus geöffnet
  ui/apps/         App-Übersicht
  ui/search/       lokale/TMDB-Suche und Sprachsuche
  ui/settings/     Einstellungen, Diagnose, Live-TV-Unterpunkt und Credits
  ui/components/   TV-Cards + Touch-Kompatibilitätswrapper für Smartphone-/Tablet-Smoke-Tests
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

EPG-Programme mit TMDB-Identität können für die vorhandene Detailansicht temporär in ein `MediaItem` überführt werden. Dadurch bleiben Detaildarstellung, Trailer und externe Suchhandoffs provider-neutral; der EPG selbst kennt keine CloudStream-/Kodi-Details.

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

XMLTV-Kategorien werden weiterhin als Typ-Hinweis genutzt. Staffel+Episode ergibt `Episode`, eindeutige Film-/Serienkategorien ergeben `Movie` bzw. `Series`. Fehlt eine belastbare Kategorie, wird der Treffer nicht mehr vorzeitig verworfen: der vorhandene TMDB-Multi-Search läuft mit `MediaType.Unknown`, anschließend gilt unverändert die zentrale strenge Confidence-Schwelle. Dadurch können bekannte Programme ohne brauchbare XMLTV-Kategorie angereichert werden, ohne schwache Treffer zu übernehmen.

Der wiederverwendbare `EpgScreen` ist weiterhin eine eigene UI-Komponente, wird im aktuellen UX aber aus dem laufenden Live-TV-Player als Guide geöffnet. Das beim Öffnen bereits ausgewählte aktuelle bzw. angesprungene Programm stößt sofort denselben TMDB-Anreicherungspfad an wie eine manuell ausgewählte Programmzeile. EPG-Suchergebnisse öffnen den passenden Sender im Player und den Guide-Kontext statt einen eigenen Hauptnavigationspunkt zu verwenden. Hat das gewählte EPG-Programm eine TMDB-Serien-/Film- oder Episodenreferenz, kann die gemeinsame Medien-Detailansicht geöffnet werden; Back stellt anschließend wieder Player + Guide-Kontext her.

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

Die Player-Informationen werden nach erfolgreichem Streamstart nach drei Sekunden ausgeblendet. Kurzes `OK` blendet eine ausgeblendete UI wieder ein; langes `OK` öffnet den Guide direkt. Solange das Overlay sichtbar ist, gehören D-Pad Hoch/Runter der UI-Fokusnavigation; bei ausgeblendeter UI zappt Hoch zum nächsten/höheren und Runter zum vorherigen/niedrigeren Sender. CH+/CH− bleiben jederzeit explizite Zapping-Tasten.

Die kompakte `Jetzt im TV`-Reihe ist der erste Fokusanker des sichtbaren Player-Overlays; vom aktuellen Sender führt Down explizit zum EPG-Button. Der Guide übernimmt danach die D-Pad-Ereignisse. Back schließt zunächst Guide bzw. sichtbares Overlay. Soll der Player anschließend verlassen werden, erscheint eine Bestätigung mit `Abbrechen` als Standardfokus; dadurch beendet ein versehentlicher weiterer Back-Druck nicht unmittelbar Live TV.

## Globale Suche

Die globale Suche arbeitet Local First. Ab zwei Zeichen werden lokale Treffer auf einem Hintergrund-Dispatcher ermittelt; längere Metadaten werden erst ab drei Zeichen einbezogen. TMDB läuft getrennt und verzögert ab drei Zeichen.

Lokale Quellen:

- installierte Apps
- sichtbare Watch-Next-Einträge
- sichtbare Preview Programs
- aktueller/gecachter Gigablue/XMLTV-EPG

Bei Watch Next werden sowohl das angereicherte gemeinsame `MediaItem` als auch die unveränderten TvProvider-Quellfelder wie Show-Titel, Episodentitel und Subtitle durchsucht. Preview Programs können zusätzlich über Channel- und Quell-App-Namen gefunden werden.

Die Ergebnis-UI trennt Quellen visuell in horizontale TV-Reihen statt alle Treffer in eine lange gemischte Liste zu legen: `Weiterschauen`, `App-Kanäle`, `TV-Programm`, `Apps` und `Filme & Serien`. Dadurch bleibt sichtbar, ob ein Treffer direkt auf diesem Gerät startbar ist oder nur aus TMDB stammt. Fokuswiederherstellung erfolgt auf die konkrete Karte innerhalb der passenden Reihe.

Sprachsuche delegiert an die auf dem Gerät vorhandene Android-Spracherkennungsaktivität und übernimmt das erkannte Ergebnis in dieselbe Suchpipeline.

TMDB-/EPG-Treffer ohne direkten Playback-Intent können optional an installierte Ziel-Apps übergeben werden. CloudStream wird über dessen `cloudstreamsearch`-Intent erkannt; die tatsächlich installierte Stable-/Prerelease-/Debug-Paketvariante wird dynamisch aufgelöst. Die aktuelle CloudStream-Such-Activity erzwingt selbst eine sichtbare Soft-Tastatur und bietet über den externen Search-Intent keinen dokumentierten Schalter zum Unterdrücken; I Launcher sendet deshalb keinen timing-basierten Back-Key-Workaround.

Kodis aktuelle Android-`ACTION_SEARCH`-Activity wird bewusst **nicht** mehr als Handoff verwendet: sie fragt intern einen `content://…media/search/<query>`-Pfad ab, den Kodis eigener Media-Provider nicht registriert. Der Adapter verwendet stattdessen Kodis exportierten Suggestions-Provider `suggestions/search_suggest_query`, führt die Provider-Abfrage außerhalb des UI-Threads aus und akzeptiert nur einen starken normalisierten Bibliothekstitel-Treffer. Für diesen Treffer wird ausschließlich die von Kodi selbst zurückgegebene `ACTION_GET_CONTENT`-/`videodb://`-Referenz an `XBMCSearchableActivity` übergeben. Liefert Kodi keinen sicheren Treffer oder ist der Provider nicht nutzbar, wird Kodi normal geöffnet. Das bleibt eine Kodi-Core-Bibliothekssuche; Add-on-spezifische Suchen sind eine getrennte spätere Integration.

## Home und Navigation

Home besteht aus einem Hero und darunter den Content-Reihen. Der Hero liegt **außerhalb** des vertikalen Reihen-Scrollcontainers und bleibt daher sichtbar, während der Benutzer durch Watch Next, `Jetzt im TV`, Preview Channels und Apps navigiert. Fokusänderungen der Karten aktualisieren nur den Hero-Inhalt, nicht die Quellreihenfolge.

Der Start-Hero wird Local First bestimmt: erster vorhandener Watch-Next-Inhalt, danach erster sichtbarer Preview-Program-Inhalt, sonst ein neutraler Launcher-Hero. Live TV übernimmt den Hero erst, wenn der Benutzer tatsächlich einen Sender fokussiert. Ein automatisch rotierendes Netzwerk-/Trend-Karussell wird bewusst nicht verwendet; das entspricht dem Produktprinzip eines ruhigen, lokalen Hero-Bereichs ohne Werbekarussell.

Artwork liegt im aktuellen TV-Layout rechts und läuft mit einem mehrstufigen horizontalen Verlauf weich in den linken Textbereich aus. Echte Backdrops werden flächig dargestellt; Poster, 4:3- und andere Quellbilder werden nach Möglichkeit `Fit` behandelt, damit zentrale Bildinhalte nicht unnötig abgeschnitten werden. Hat ein Medium ein Titellogo, wird derselbe Titel nicht zusätzlich als große Überschrift wiederholt; Quell-App-Namen werden im Medien-Hero nicht erneut eingeblendet. Der Textbereich zeigt ergänzende Informationen wie Medientyp/Staffel-Episode, Jahr und Bewertung statt Fortschritt oder andere unmittelbar auf der fokussierten Karte sichtbare Werte zu duplizieren. Lange Beschreibungen starten erst nach einer Lesepause und scrollen langsam innerhalb eines begrenzten Textfensters.

Der Hero selbst ist fokussierbar. Bei Medien öffnet `OK` die vorhandene provider-neutrale Detailansicht; bei Apps wird die App geöffnet.

Die primäre Navigation ist bewusst klein: `Home · Suche · Apps · Einstellungen`. Der aktive Punkt wird nur durch einen Rahmen markiert. Auf Home darf die Navigationszeile verschwinden, sobald der Benutzer tatsächlich vertikal in die Content-Reihen scrollt; kleine Layout-/Bring-into-view-Bewegungen beim horizontalen Kartenfokus werden durch eine kleine Scrollschwelle ignoriert, damit die Navigation beim Links/Rechts-Navigieren nicht springt. Live-TV-/Gigablue-Konfiguration liegt unter Einstellungen; EPG ist eine Player-Funktion und kein eigener Hauptpunkt.

## Touch-Smoke-Tests

TV-/D-Pad-Bedienung bleibt die Produktquelle der Wahrheit. Für schnellere UI-Smoke-Tests kann derselbe Development-Build zusätzlich auf Smartphone/Tablet installiert werden; `android.software.leanback` ist dafür nicht zwingend erforderlich.

Compose-for-TV-Komponenten bleiben für TV-Fokus und Fernbedienung erhalten. Ein kleiner `TouchButton`-/`TouchCard`-Wrapper ergänzt ausschließlich Pointer-Taps. Scrollbare Home-, Such-, Apps-, Einstellungen-, EPG- und Live-TV-Bereiche bekommen zusätzlich einen Pointer-Drag-Fallback, ohne die bestehende D-Pad-Navigation oder Quellreihenfolge umzubauen. Watch Next und Preview Channels können auf Nicht-TV-Geräten andere bzw. keine Daten liefern, weil TvProvider-Inhalte gerätelokal sind.

Die provider-neutrale Medien-Detailseite verwendet ebenfalls einen echten vertikalen Scroll-Container mit demselben Touch-Fallback. Lange Beschreibungen werden nicht abgeschnitten. Aktionsbuttons liegen in einem `FlowRow`, sodass sie auf schmalen Testgeräten umbrechen und weiterhin erreichbar bleiben; dieser Pfad wurde auf einem Smartphone praktisch bestätigt. Auf TV-Breite bleiben die Compose-for-TV-Buttons und D-Pad-Fokuspfade maßgeblich.

## Detailnavigation und Trailer

Kurzes `OK` auf Watch Next startet weiterhin den vorhandenen Source-/Playback-Intent. `INFO` oder lange `OK` öffnet Details. Beim langen TV-OK wird die Navigation erst nach dem konsumierten `ACTION_UP` ausgeführt. Damit kann der physische OK-Release nicht mehr den frisch fokussierten `Fortsetzen/Wiedergeben`-Button der neu aufgebauten Detailseite auslösen. Pointer-Long-Press bleibt davon unabhängig.

Hero-Medien und TMDB-verknüpfte EPG-Programme öffnen ebenfalls die provider-neutrale Detailansicht. Beim Öffnen wird die erste sinnvolle Aktion fokussiert. Bei direkt abspielbaren Watch-Next-Inhalten bleibt `Fortsetzen/Wiedergeben` vorn. Bei TMDB-/EPG-Details ohne direkten Player folgen verfügbare externe Suchziele in der Reihenfolge CloudStream, Kodi, danach Trailer und Zurück. Externe Suchbuttons verwenden Suchsymbol + App-Name statt erklärender Langtexte; das Symbol übernimmt über Compose for TV `LocalContentColor` dieselbe Fokusfarbe wie der Buttontext.

Trailer werden bevorzugt aus TMDB-Video-Metadaten aufgelöst. Wenn eine konkrete YouTube-ID vorhanden ist, startet eine eigene interne Trailer-Activity mit WebView und `WebChromeClient`-Fullscreen-Custom-View-Unterstützung. Es findet keine Stream-Extraktion statt. Wenn keine konkrete Video-ID vorhanden ist, bleibt die externe YouTube-Suche der Fallback. Die interne Trailerwiedergabe mit Bild und Ton wurde auf TV-Hardware bestätigt.

## Room Cache und Local First

Room-Version 3 enthält `epg_channel_mappings` und `epg_programs`; Migration `2 → 3` erhält den bestehenden TMDB-Cache. Der OpenWebif-Snapshot liegt lokal in SharedPreferences. XMLTV-Programme und Sender-Mappings liegen in Room.

Live-TV-Streamadressen werden bewusst **nicht** gecacht.

## Development-Publishing

Signierte Development-Builds laufen über den `downloads`-Kanal. Vor einer Test-APK laufen `testDebugUnitTest` und `assembleDebug`. Automatisierter Build ersetzt keinen TV-Gerätetest; insbesondere D-Pad-/Fokusänderungen, Kodi-Handoff und Player-Navigation bleiben bis zur Hardwarebestätigung als offen dokumentiert.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService` mit `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true`. Das TCL-/Android-13+-Restricted-Settings-Verhalten bei lokal installierten APKs bleibt ein separates Distributionsthema.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
