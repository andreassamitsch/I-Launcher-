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
- [x] offizielle Android-Home-Rolle als bevorzugte Launcher-Aktivierung
- [x] Accessibility-Fallback erkennt HOME-Key und System-Launcher-Fenster
- [x] Hilfe für Android „Eingeschränkte Einstellungen“ bei seitlich installierten Accessibility-Services
- [ ] realer TV-Gerätetest für Home-Rolle / Accessibility-Fallback

## Distribution / Updates

- [x] feste öffentliche Downloader-URL über Branch `downloads`
- [x] CI-Versionierung mit steigendem `versionCode` / `versionName`
- [x] `update.json` mit Version, APK-URL und SHA-256
- [x] automatische In-App-Prüfung auf neue Versionen beim Start
- [x] Update im Hintergrund über Android `DownloadManager`
- [x] SHA-256-Prüfung vor Installation
- [x] TV-optimierte Update-Steuerung in Einstellungen und Hinweis in der Hauptnavigation
- [x] Installations-Intent zum Android-Systeminstaller
- [x] direkte Freigabe für „Installation aus dieser Quelle“ öffnen
- [x] Update-Kanal sperrt automatische Installation, solange keine stabile Development-Signatur vorhanden ist
- [ ] dauerhaften Development-Signing-Key als geschützte GitHub-Secrets hinterlegen
- [ ] einmalige Neuinstallation auf die stabile Development-Signatur durchführen
- [ ] realen Update-von-Version-A-auf-Version-B-Gerätetest durchführen

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
