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
- später integrierter Live-TV-Player mit Media3
- vollständige D-Pad-/Fernbedienungsbedienung
- Local First

## Status

**Phase 4 – Trailer ist abgeschlossen. Phase 5 – Gigablue/OpenWebif und Phase 6 – EPG sind vollständig implementiert und gebaut. Für beide Phasen steht noch der gemeinsame reale TCL-/Gigablue-/XMLTV-Gerätetest aus.**

Der bestätigte Unterbau umfasst:

- Android-TV-Launcher/Home-App mit D-Pad-Focus
- Android Watch Next über TvProvider einschließlich CloudStream
- gemeinsames provider-neutrales `MediaItem`-/`MediaSource`-Modell
- konservative TMDB-Auflösung mit Room-Cache
- Film-/Serien-/Episodendaten und Artwork
- provider-neutrale Detailseite mit Focus-Rückgabe
- TMDB-/YouTube-Trailer mit Such-Fallback

Phase 5 ergänzt direkt über Enigma2/OpenWebif:

- lokale Receiver-Konfiguration mit optionaler HTTP-Basic-Authentifizierung
- Bouquets und Sender in Receiver-Reihenfolge
- Picons
- EPG Now/Next
- auswählbares Bouquet
- Local-First-Snapshot für die zuletzt bekannten TV-Daten
- eigene `Live TV`-Ansicht
- `Jetzt im TV` auf Home mit Senderlogo, aktueller Sendung, Zeit, Fortschritt und nächster Sendung
- fünfminütige Hintergrundaktualisierung ohne den Launcher-Start zu blockieren

Phase 6 ergänzt auf dem Branch `agent/phase-6-epg` eine zweite, reine Metadatenquelle für den EPG:

- M3U-Metadaten werden eingelesen, standardmäßig aus `https://riedl-dach.at/tv.m3u`
- aus der M3U werden nur `x-tvg-url`, `tvg-id`, `tvg-id-ALT`, `tvg-name`, Logos sowie vorhandene Enigma2-Service-Reference-Hinweise verwendet
- Wiedergabe-/IPTV-URLs aus dieser M3U werden weder als Live-TV-Quelle verwendet noch persistiert
- Gigablue/OpenWebif bleibt maßgeblich für Bouquet, Senderidentität, Reihenfolge und späteren Stream
- Senderzuordnung erfolgt bevorzugt über die Enigma2-Service-Reference, danach über konservatives Namensmatching; unsichere Treffer können manuell zugeordnet werden
- die Live-TV-Diagnose zeigt für den Gerätetest XMLTV-ID und Match-Methode pro Sender, ohne Receiver-Adresse oder Zugangsdaten offenzulegen
- die referenzierte XMLTV-Datei wird als Stream verarbeitet; GZIP wird automatisch erkannt
- nur die tatsächlich gemappten XMLTV-Sender und ein begrenztes Zeitfenster werden in den Speicher übernommen
- XMLTV-Metadaten ergänzen OpenWebif-Programme, überschreiben aber nicht zuverlässig vorhandene OpenWebif-Zeit-/Eventdaten; schwache zeitliche Überschneidungen werden nicht als Match akzeptiert
- vollständiger EPG je Sender ist als eigener TV-Bereich verfügbar und öffnet beim aktuell laufenden Programm
- XMLTV-Programm, Sendermapping und Metadaten werden lokal gecacht
- der Cache wird beim Start parallel zum Receiver-Netzwerkrefresh veröffentlicht, damit vorhandene EPG-Daten Local First sichtbar werden
- Room wird verlustfrei von Version 2 auf 3 migriert
- aktuelle Film-/Serienprogramme werden konservativ über den vorhandenen TMDB-Resolver mit Artwork/Details angereichert; weitere Guide-Einträge bei Auswahl
- `Jetzt im TV` kann dadurch Programm-Artwork erhalten, das Sender-Picon bleibt erhalten
- die Home-Seite ist vertikal scrollbar, sodass `Weiterschauen`, `Jetzt im TV` und `Apps` per D-Pad erreichbar sind

OpenWebif-Zugangsdaten werden nur lokal gespeichert, nicht geloggt und durch `allowBackup=false` nicht über Android Auto Backup ausgelagert. Externe EPG-Quellen erhalten keine Receiver-Zugangsdaten.

Auf dem TCL bereits verifiziert sind Launcher, Watch Next, TMDB, Detailnavigation, Focus-Rückgabe und Trailer einschließlich Update `dev.45` → `dev.47`.

Bestätigter Phase-4-Build: **`0.1.0-dev.47` (`26000047`)**.

Bestätigter Phase-5-Build: **`0.1.0-dev.51` (`26000051`)**, `updateCompatible=true`, `tmdbConfigured=true`; Unit-Tests und `assembleDebug` liefen im signierten Publisher erfolgreich. Die Phase-5-Funktionen gelten erst nach dem realen TCL + Gigablue-X3-Test als hardwareverifiziert.

Für Phase 6 sind `testDebugUnitTest`, `assembleDebug`, Development-Signierung und Veröffentlichung im Downloadkanal erfolgreich. Der reale XMLTV-Inhalt von `epg.gz` ist bewusst noch nicht als hardwaregetestet dokumentiert: Die Referenzdatei ist binär komprimiert und wird im Zielbuild direkt gestreamt/dekomprimiert. Parser, Matching, Merge und EPG-Startposition werden automatisiert mit repräsentativen XMLTV-/M3U-Testdaten geprüft; zusätzlich ist der Test gegen die reale Quelle auf dem TV erforderlich.

Watch Next liefert CloudStream-Einträge über die reguläre Android-TvProvider-Schnittstelle. Eine CloudStream-spezifische Integration bleibt deshalb bewusst außen vor.

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
- Coil 3.5.0
- Room 2.8.4
- Retrofit 3.0.0
- OkHttp 5.3.0

## Lizenz

MIT
