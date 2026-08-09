# I Launcher

I Launcher ist ein werbefreier, content-zentrierter Android-TV-Launcher in Kotlin und Compose for TV.

## Ziele

- schnelle TV-Home-Oberfläche ohne Werbung
- Android Watch Next / „Weiterschauen“
- Preview Channels installierter Apps
- TMDB-Metadaten für Filme, Serien und Episoden
- Trailer über TMDB/YouTube
- direkte Gigablue-X3-/Enigma2-/OpenWebif-Integration
- vollständiger EPG mit XMLTV- und TMDB-Anreicherung
- integrierter Live-TV-Player mit Media3
- vollständige D-Pad-/Fernbedienungsbedienung
- Local First

## Status

**Phasen 1 bis 7 sind funktional abgeschlossen und auf dem Zielgerät bestätigt.**

Der bestätigte Unterbau umfasst:

- Android-TV-Launcher/Home-App mit D-Pad-Focus
- Android Watch Next über TvProvider einschließlich CloudStream
- gemeinsames provider-neutrales `MediaItem`-/`MediaSource`-Modell
- konservative TMDB-Auflösung mit Room-Cache
- Film-/Serien-/Episodendaten und Artwork
- provider-neutrale Detailseite mit Focus-Rückgabe
- TMDB-/YouTube-Trailer mit Such-Fallback
- direkte Gigablue-/OpenWebif-Verbindung
- Bouquets und Sender in Receiver-Reihenfolge
- Picons und OpenWebif Now/Next
- `Jetzt im TV` auf Home
- M3U/XMLTV-Senderzuordnung einschließlich Enigma2-Service-Reference und manueller Zuordnung
- vollständiger XMLTV-EPG mit Local-First-Room-Cache
- XMLTV/OpenWebif-Merge und konservative TMDB-Anreicherung für TV-Programme
- interner Media3-Live-TV-Player mit OpenWebif-Streamauflösung und Zapping
- D-Pad-Navigation und Focus-Rückgabe auf Home, EPG und Live TV

Phase 5 und Phase 6 wurden gemeinsam auf realer TCL-/Gigablue-X3-Hardware gegen die reale `riedl-dach.at` M3U/XMLTV-Quelle verifiziert. Dazu gehören Receiver-Verbindung, Bouquet-/Senderreihenfolge, Picons, Now/Next, XMLTV-Mapping, vollständiger EPG, TMDB-Artwork, D-Pad/Focus, Update/Migration und Offline-/Cache-Verhalten.

## Aktueller Entwicklungszweig – Suche / Preview Channels / UI-Polish

Der aktuelle gestapelte Entwicklungsstand ergänzt Preview Channels, globale Suche und eine überarbeitete Content-Home-Oberfläche. Diese Bereiche bleiben bis zum abschließenden Gerätetest bewusst in Draft-PRs.

Der UI-Polish-Stand umfasst zusätzlich:

- fest sichtbaren, fokussierbaren Hero-Bereich auf Home; der Inhalt folgt dem fokussierten Content und `OK` öffnet bei Medien die Detailansicht
- kompaktere Hauptnavigation `Home · Suche · Apps · Einstellungen`, nur mit Rahmenmarkierung für den aktiven Bereich; auf Home kann die Navigation beim Scrollen der Reihen ausblenden
- Live-TV-/Gigablue-Konfiguration als Unterpunkt der Einstellungen statt eigener Hauptnavigation
- EPG als TV-Guide direkt im laufenden Live-TV-Player statt eigener Hauptnavigation
- Live-TV-Infoleisten mit automatischem Ausblenden nach drei Sekunden, `OK` zum Einblenden und Back-First-Hide-Verhalten
- interne Trailerwiedergabe in einer eigenen Launcher-Activity mit WebView-Fullscreen-Videooberfläche; externe YouTube-Suche bleibt Fallback, wenn keine konkrete TMDB-Video-ID vorliegt
- lokale Suche berücksichtigt zusätzlich die unveränderten Android-Watch-Next-Quelltitel und Preview-Channel-Namen
- Sprachsuche über die auf dem TV verfügbare Android-Spracherkennungsaktivität
- CloudStream-Suchhandoff erkennt Stable-, Prerelease-, Debug- und kombinierte Development-Paketvarianten dynamisch über den `cloudstreamsearch`-Intent

Automatisiert geprüft und veröffentlicht ist der aktuelle Code als Development-Testbuild **`0.1.0-dev.115` (`26000115`)**. Build und Unit-Tests sind grün; die oben genannten UI-/Hardwareänderungen sind damit ausdrücklich noch nicht als Gerätetest bestätigt.

## Phase 7 – Live TV

Phase 7 ergänzt:

- Media3/ExoPlayer als internen Player
- Streamauflösung über OpenWebifs eigenes `web/stream.m3u?ref=…`, damit der Receiver selbst den tatsächlichen Stream-Port bzw. direkten Stream bestimmt
- keine Persistierung oder Protokollierung von Stream-URLs, Session-IDs oder Stream-Zugangsdaten
- MPEG-TS-Wiedergabe über Media3 Progressive Media Source
- HLS-Wiedergabe, falls OpenWebif einen HLS-Stream zurückliefert
- temporäre Streaming-Authentifizierung wird aus URL-Userinfo entfernt und nur flüchtig als HTTP-Header an Media3 weitergegeben
- Start des internen Players direkt aus `Jetzt im TV`
- Zapping in unveränderter Gigablue-Senderreihenfolge
- D-Pad ↑/↓ sowie CH+/CH− für Senderwechsel
- alter Stream wird beim Zappen sofort gestoppt, bevor der Zielstream neu aufgelöst wird
- TV-Overlay mit Picon, aktueller/nächster Sendung, Bouquet-Position, Lade- und Fehlerstatus
- exakte Focus-Rückgabe auf die zuvor gestartete `Jetzt im TV`-Karte
- Unit-Tests für Stream-Parsing, Auth-/Session-Sanitizing und Zapping

Android CI und der signierte Development-Publisher laufen für diesen Stand erfolgreich durch. Hardware-Testbuild **`0.1.0-dev.68` (`26000068`)** wurde auf TCL + Gigablue X3 erfolgreich getestet: interner Streamstart, Video/Audio, Zapping, Overlay und Rückkehrverhalten funktionieren auf dem Zielgerät.

## Datenschutz / Sicherheit

OpenWebif-Zugangsdaten werden nur lokal gespeichert, nicht geloggt und durch `allowBackup=false` nicht über Android Auto Backup ausgelagert. Externe EPG-Quellen erhalten keine Receiver-Zugangsdaten. Phase 7 behandelt vom Receiver gelieferte Stream-Adressen und temporäre Streaming-Authentifizierung ausschließlich flüchtig im Arbeitsspeicher.

Watch Next liefert CloudStream-Einträge über die reguläre Android-TvProvider-Schnittstelle. Eine CloudStream-spezifische Integration bleibt deshalb für das Einlesen von Watch Next bewusst außen vor; der optionale Suchhandoff aus TMDB-Ergebnissen verwendet ausschließlich die von CloudStream bereitgestellte externe Suchschnittstelle.

Das TCL-/Google-TV-Thema rund um Android 13+ `Covered Applications` / `Restricted Settings` bei lokal installierten APKs bleibt als separates Distributionsthema offen und blockiert die Content-Phasen nicht.

Siehe:

- [`AGENTS.md`](AGENTS.md) – verbindliche Entwicklungsrichtlinien
- [`ROADMAP.md`](ROADMAP.md) – Entwicklungsphasen
- [`ARCHITECTURE.md`](ARCHITECTURE.md) – Architektur

## Stack

Kotlin · Jetpack Compose · Compose for TV · AndroidX · Coroutines/Flow · Room · Hilt · Retrofit/OkHttp · Coil · Media3

## Build-Basis

- Android Gradle Plugin 9.3.1
- Gradle 9.5.0 (CI)
- compileSdk 36
- targetSdk 36
- minSdk 26
- Compose BOM 2026.06.00
- Compose for TV 1.1.0
- Media3 1.10.1
- Coil 3.5.0
- Room 2.8.4
- Retrofit 3.0.0
- OkHttp 5.3.0

## Lizenz

MIT
