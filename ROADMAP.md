# I Launcher Roadmap

Die Roadmap folgt den verbindlichen Richtlinien in `AGENTS.md`. Änderungen an Prioritäten müssen dort bzw. in diesem Dokument nachvollziehbar gehalten werden.

## Phase 1 – Launcher MVP

Ziel: Eine installierbare Android-TV-Home-App mit sauberer D-Pad-Bedienung und App-Start.

- [x] Kotlin/Compose-for-TV-Projekt
- [x] Android-TV-Launcher/Home-Intent
- [x] App-Name `I Launcher`
- [x] installierte TV-Apps ermitteln
- [x] Apps per Deep/Launch Intent öffnen
- [x] Home-Ansicht mit TV-optimierten Abständen
- [x] Basisnavigation Home / Apps / Einstellungen
- [ ] Focus/D-Pad-Verhalten – implementiert, realer TV-Test ausstehend
- [x] Unit-Test für App-Sortierung/Filterung soweit sinnvoll
- [x] CI-Build
- [x] Debug-APK als CI-Artefakt
- [ ] realer TV-Gerätetest

## Distribution / Updates

- [x] feste öffentliche URL für die jeweils aktuelle Development-APK über GitHub Release `dev`
- [ ] Release-Versionierung mit sauberem `versionCode` / `versionName`
- [ ] In-App-Prüfung auf neue Versionen
- [ ] Update im Hintergrund herunterladen
- [ ] TV-optimierter Dialog „Update installieren“
- [ ] Installations-Intent starten; auf normalen Android-TV-/Google-TV-Geräten bleibt die Systembestätigung für die APK-Installation erforderlich
- [ ] optional automatischer Update-Check beim Start, ohne den Launcher-Start zu blockieren

## Phase 2 – Watch Next

- Android TvProvider / Watch Next
- vorhandene Reihenfolge beibehalten
- Fortschritt und Metadaten darstellen
- Quell-App über vorhandenen Intent/Deep Link öffnen
- auf dem Zielgerät gegen Arc/CloudStream vergleichen

## Phase 3 – TMDB

- API-Client
- gemeinsames Medienmodell
- Resolver mit Confidence
- Room-Mapping/Cache
- Poster, Backdrops, Logos
- Serien-/Episodendaten
- Detailseite

## Phase 4 – Trailer

- TMDB Videos
- YouTube-ID
- YouTube-Suche nur als Fallback
- Trailerwiedergabe

## Phase 5 – Gigablue / OpenWebif

- Verbindung und Authentifizierung
- Bouquets
- Sender
- EPG Now/Next
- `Jetzt im TV` auf Home

## Phase 6 – EPG

- kompletter EPG-Guide
- TMDB-Anreicherung
- Bilder und Details

## Phase 7 – Live TV

- Media3-Player
- OpenWebif Streams
- Zapping
- TV-Player-UI

## Phase 8 – optionale Provider

Nur wenn Android-Standardschnittstellen nicht ausreichen:

- Kodi
- Jellyfin
- Plex
- CloudStream
- weitere Provider
