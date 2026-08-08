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
- [x] Focus/D-Pad-Verhalten im aktuellen Launcher-/Watch-Next-Stand auf TCL grundsätzlich verifiziert
- [x] Unit-Test für App-Sortierung/Filterung soweit sinnvoll
- [x] CI-Build
- [x] Debug-APK als CI-Artefakt
- [x] offizielle Android-Home-Rolle als bevorzugte Launcher-Aktivierung
- [x] Accessibility-Fallback erkennt HOME-Key und System-Launcher-Fenster
- [x] `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true` korrekt konfiguriert
- [x] TCL/Google-TV-Gerätetest bei ADB-Installation: Accessibility-Fallback lässt sich aktivieren und HOME öffnet I Launcher
- [x] Android-13+-Restricted-Settings bei lokaler/heruntergeladener APK als Ursache für zurückspringenden Accessibility-Schalter berücksichtigt
- [x] Installationsquellen-Diagnose und Einrichtungsführung vorhanden
- [x] Standard-`MAIN`/`LAUNCHER`-Entry zusätzlich zu HOME/LEANBACK für reguläre Front-Door-/Installer-Öffnen-Funktion
- [ ] TCL-spezifisches Covered-Applications-/Restricted-Settings-Verhalten bei normaler APK-Installation später separat verbessern; blockiert die Content-Phasen nicht
- [x] zentrales dunkles TV-Material-Farbschema mit kontrastreichen Content-Farben

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
- [x] Development-Downloadkanal auf den aktiven Entwicklungsbranch umstellbar
- [x] Publisher-Concurrency verhindert, dass ältere parallele CI-Läufe einen neueren Development-Build überschreiben
- [ ] realen Update-von-Version-A-auf-Version-B-Gerätetest als eigener Distributionstest dokumentieren

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
- [x] neue Sortierung auf dem TCL gegen andere Launcher plausibilisiert und vom Benutzer als funktionierend bestätigt
- [x] unerwünschte Browser-/sonstige Quellen lassen sich auf dem TCL per Quellenfilter ausblenden
- [x] Watch-Next-Daten inklusive CloudStream werden über Android TvProvider sichtbar; kein CloudStream-Sonderweg nötig
- [x] D-Pad/Scroll/Fokus der aktuellen Watch-Next-Reihe im Gerätetest ohne blockierenden Fehler

**Phase 2 ist funktional abgeschlossen.** Das separate TCL-/Covered-Applications-Thema des Accessibility-Fallbacks bleibt als Distribution-/Gerätebesonderheit offen und blockiert Phase 3 nicht.

## Phase 3 – TMDB

- [x] Retrofit/OkHttp API-Client mit extern konfiguriertem Read-Access-Token
- [x] gemeinsames Medienmodell für Android TV und spätere Provider
- [x] deterministisches Titel-/Jahr-/Staffel-/Episoden-Parsing
- [x] Resolver mit konservativer Confidence-Schwelle und Source-Fallback
- [x] Room-Mapping/Cache inklusive negativer No-Match-Ergebnisse
- [x] Cache-Mapping nur wiederverwenden, wenn Titel/Jahr/Staffel/Episode weiterhin zur Quellidentität passen
- [x] Local-First-Anreicherung: Quelle sofort anzeigen, TMDB danach begrenzt nachladen
- [x] Android `COLUMN_TYPE` und `COLUMN_RELEASE_DATE` als Resolver-Hinweise übernehmen
- [x] Poster-/Backdrop-/Logo-/Episode-Still-Infrastruktur über TMDB `/configuration`
- [x] Serien-/Episodendaten-Unterbau inklusive Episode-Detail-Endpoint
- [x] Cache-Refresh nach 30 Tagen und harte Löschung nach 180 Tagen
- [x] Unit-Tests für Parser, Confidence, Android-Mapping und Artwork-Priorität
- [x] CI-Build des vollständigen Phase-3-Unterbaus inklusive Detailseite grün
- [x] Detailseite implementiert, ohne den normalen Watch-Next-Direktstart zu ersetzen: OK = Wiedergabe, INFO/lange OK = Details
- [x] Home-Scrollposition beim Wechsel in/aus Details im Compose-State erhalten
- [x] TCL-Gerätetest: Direktstart, INFO/lange OK, Detailseite und Back-Navigation funktionieren grundsätzlich
- [x] reproduzierte Focus-Lücke analysiert: Home-Subtree wird bei Details aus der Composition entfernt; LazyListState allein erhält keinen Focus-Owner
- [x] explizite Focus-Rückgabe über stabile Watch-Next-Source-ID + `FocusRequester` nach `scrollToItem` und folgendem Compose-Frame implementiert
- [ ] Focus-Rückgabe auf exakt dieselbe Watch-Next-Karte auf TCL erneut verifizieren
- [x] TMDB-Attribution im Bereich `Über / Credits` mit genehmigtem TMDB-Logo und vorgeschriebenem Hinweis implementiert
- [x] signierter Publisher konsumiert `IL_TMDB_READ_ACCESS_TOKEN` ausschließlich als geschütztes GitHub-Secret und veröffentlicht `tmdbConfigured=true/false` zur Diagnose
- [x] `IL_TMDB_READ_ACCESS_TOKEN` im Repository als Secret vorhanden und über harten CI-Prüfschritt verifiziert
- [x] Publisher veröffentlicht ab aktiver Phase 3 keinen source-only Build mehr, wenn das TMDB-Secret fehlt
- [x] signierter Live-TMDB-Build `0.1.0-dev.40` (`26000040`) veröffentlicht; `updateCompatible=true`, `tmdbConfigured=true`
- [x] TMDB-Diagnose für Build-Aktivierung, aufgelöste ID, Typ und Confidence ohne Secret/URLs implementiert
- [ ] realen TCL-Gerätetest der aktiven TMDB-Anreicherung, Serien-/Episodenauflösung, Artwork-Auswahl und Cache-Nutzung durchführen

**Phase 3 bleibt bis zum realen TCL-Test von Focus-Rückgabe und aktiver TMDB-Anreicherung offen.**

## Phase 4 – Trailer

- [ ] TMDB Videos
- [ ] YouTube-ID
- [ ] YouTube-Suche nur als Fallback
- [ ] Trailerwiedergabe

## Phase 5 – Gigablue / OpenWebif

- [ ] Verbindung und Authentifizierung
- [ ] Bouquets
- [ ] Sender
- [ ] EPG Now/Next
- [ ] `Jetzt im TV` auf Home

## Phase 6 – EPG

- [ ] kompletter EPG-Guide
- [ ] TMDB-Anreicherung
- [ ] Bilder und Details

## Phase 7 – Live TV

- [ ] Media3-Player
- [ ] OpenWebif Streams
- [ ] Zapping
- [ ] TV-Player-UI

## Phase 8 – optionale Provider

Nur wenn Android-Standardschnittstellen nicht ausreichen:

- [ ] Kodi
- [ ] Jellyfin
- [ ] Plex
- [ ] CloudStream
- [ ] weitere Provider
