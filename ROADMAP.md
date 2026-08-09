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
- [x] realer Update-von-Version-A-auf-Version-B-Gerätetest im Übergang `dev.45` → `dev.47` erfolgreich; Room-Migration und Update-Signatur bestätigt

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
- [x] deterministisches Titel-/Jahr-/Staffel-/Episoden-Parsing inklusive `Sx:Ex`-Quelltiteln
- [x] Resolver mit konservativer Confidence-Schwelle und Source-Fallback
- [x] Room-Mapping/Cache inklusive negativer No-Match-Ergebnisse
- [x] Cache-Mapping nur wiederverwenden, wenn Titel/Jahr/Staffel/Episode weiterhin zur Quellidentität passen
- [x] Local-First-Anreicherung: Quelle sofort anzeigen, TMDB danach progressiv nachladen
- [x] alle sichtbaren Watch-Next-Einträge in kleinen Batches anreichern; keine feste 12-Einträge-Grenze
- [x] einmaliger Retry noch ungelöster Einträge ohne unnötige Wiederholung negativer Cache-Treffer
- [x] Android `COLUMN_TYPE` und `COLUMN_RELEASE_DATE` als Resolver-Hinweise übernehmen
- [x] Poster-/Backdrop-/Logo-/Episode-Still-Infrastruktur über TMDB `/configuration`
- [x] Serien-/Episodendaten-Unterbau inklusive Episode-Detail-Endpoint
- [x] Cache-Refresh nach 30 Tagen und harte Löschung nach 180 Tagen
- [x] Unit-Tests für Parser, Confidence, Android-Mapping und Artwork-Priorität
- [x] CI-Build des vollständigen Phase-3-Unterbaus inklusive Detailseite grün
- [x] Detailseite implementiert, ohne den normalen Watch-Next-Direktstart zu ersetzen: OK = Wiedergabe, INFO/lange OK = Details
- [x] Home-Scrollposition beim Wechsel in/aus Details im Compose-State erhalten
- [x] TCL-Gerätetest: Direktstart, INFO/lange OK, Detailseite und Back-Navigation funktionieren
- [x] explizite Focus-Rückgabe über stabile Watch-Next-Source-ID + `FocusRequester` nach `scrollToItem` implementiert
- [x] Focus-Rückgabe auf exakt dieselbe Watch-Next-Karte auf TCL verifiziert
- [x] TMDB-Attribution im Bereich `Über / Credits` mit genehmigtem TMDB-Logo und vorgeschriebenem Hinweis implementiert
- [x] signierter Publisher konsumiert `IL_TMDB_READ_ACCESS_TOKEN` ausschließlich als geschütztes GitHub-Secret
- [x] Publisher veröffentlicht keinen Phase-3-Development-Build ohne TMDB-Secret
- [x] TMDB-Diagnose für Build-Aktivierung, aufgelöste ID, Typ und Confidence ohne Secret/URLs implementiert
- [x] realer TCL-Gerätetest von aktiver TMDB-Anreicherung, Serien-/Episodenauflösung, Artwork-Auswahl, progressivem Nachladen und Focus-Rückkehr bestanden

**Phase 3 ist funktional abgeschlossen und auf realer TCL-Hardware bestätigt.**

## Phase 4 – Trailer

- [x] TMDB Videos über Movie-/TV-/Episode-Details einlesen
- [x] bevorzugte YouTube-ID deterministisch auswählen und im gemeinsamen Medienmodell abbilden
- [x] Episode-Trailer vor Serien-Trailer priorisieren
- [x] Trailer-ID und „kein Treffer“-Status in Room cachen
- [x] Room-Migration 1 → 2 ohne Löschen bestehender Phase-3-Caches implementieren
- [x] YouTube-Suche nur als Fallback anbieten, wenn TMDB keine verwertbare Video-ID liefert
- [x] Traileraktion in der provider-neutralen Detailseite implementieren
- [x] Trailer/YouTube-Suche über Android `ACTION_VIEW` delegieren; keine YouTube-Stream-Extraktion
- [x] Unit-Tests für Trailer-Auswahl und Episode-vor-Serie-Priorität
- [x] signierten Development-Build `0.1.0-dev.47` erfolgreich mit Unit-Tests und `assembleDebug` veröffentlicht
- [x] realer TCL-Gerätetest von Datenbankmigration, Trailerstart, Such-Fallback und Rückkehrverhalten bestanden

**Phase 4 ist funktional abgeschlossen und auf realer TCL-Hardware bestätigt.**

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
