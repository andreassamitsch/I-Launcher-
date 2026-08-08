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
- [ ] Focus/D-Pad-Verhalten – implementiert, laufende reale TV-Tests
- [x] Unit-Test für App-Sortierung/Filterung soweit sinnvoll
- [x] CI-Build
- [x] Debug-APK als CI-Artefakt
- [x] offizielle Android-Home-Rolle als bevorzugte Launcher-Aktivierung
- [x] Accessibility-Fallback erkennt HOME-Key und System-Launcher-Fenster
- [x] `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true` korrekt konfiguriert
- [x] TCL/Google-TV-Gerätetest bei ADB-Installation: Accessibility-Fallback lässt sich aktivieren und HOME öffnet I Launcher
- [x] Android-13+-Restricted-Settings bei lokaler/heruntergeladener APK als Ursache für zurückspringenden Accessibility-Schalter berücksichtigt
- [x] Installationsquellen-Diagnose und klare App-Info- / „Eingeschränkte Einstellungen zulassen“-Führung
- [x] Standard-`MAIN`/`LAUNCHER`-Entry zusätzlich zu HOME/LEANBACK für reguläre Front-Door-/Installer-Öffnen-Funktion
- [ ] TCL-Gerätetest nach normaler APK-Installation: Restricted Settings erlauben, Accessibility aktivieren, HOME testen
- [ ] TCL-Gerätetest: Paketinstaller bietet nach Installation „Öffnen“ an
- [x] zentrales dunkles TV-Material-Farbschema mit kontrastreichen Content-Farben
- [ ] abschließender realer TV-Gerätetest für Focus/Scroll/Lesbarkeit nach den letzten UI-Fixes

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
- [x] dauerhafter Development-Signing-Key als geschützte GitHub-Secrets hinterlegt (`updateCompatible=true` verifiziert)
- [x] Development-Downloadkanal auf den aktiven Phase-2-Branch umgestellt
- [ ] realen Update-von-Version-A-auf-Version-B-Gerätetest durchführen

## Phase 2 – Watch Next

- [x] `android.permission.READ_TV_LISTINGS` im Manifest deklarieren und als Runtime-Berechtigung anfordern
- [x] fehlende TV-Listings-Berechtigung explizit erkennen statt fälschlich „0 Einträge“ zu melden
- [x] gemeinsame Berechtigungsbasis für Watch Next und spätere Preview Channels
- [x] Android TvProvider / Watch Next einlesen
- [x] Android-Sortierhinweis `last_engagement_time_utc_millis DESC` verwenden; mit Arc-Implementierung abgeglichen
- [x] vom TvProvider angeforderte Reihenfolge im Mapper und nach Quellenfilterung unverändert erhalten
- [x] Unit-Test für Watch-Next-Sortieranforderung und Mapping-Reihenfolge
- [x] Quellenfilter pro App/Package für die Home-Reihe; Rohdaten bleiben in Diagnose erhalten
- [x] Titel, Staffel/Episode, Bild und Fortschritt darstellen
- [x] Quell-App / Package für Diagnose erfassen
- [x] Quell-App über vorhandenen Intent/Deep Link öffnen
- [x] TvProvider-Änderungen per ContentObserver live beobachten
- [x] Diagnoseansicht mit Rohreihenfolge, Watch-Next-Typ, Engagement-Zeit und relevanten TvProvider-Feldern
- [x] keine vollständigen Deep-Link-/Bild-URLs in Diagnose oder Logs ausgeben
- [ ] neue Sortierung auf dem TCL gegen Arc/Projectivy vergleichen
- [ ] Browser-/unerwünschte Quellen auf dem TCL per Quellenfilter ausblenden und Verhalten verifizieren
- [ ] CloudStream-Einträge, Bilder, Fortschritt und Deep Links auf realer TV-Hardware verifizieren
- [ ] D-Pad/Scroll/Fokus der Watch-Next-Reihe auf realer TV-Hardware verifizieren

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
