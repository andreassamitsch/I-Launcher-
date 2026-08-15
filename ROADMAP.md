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
- [x] Android-13+-Restricted-Settings bei lokal installierten APKs als Ursache für zurückspringenden Accessibility-Schalter berücksichtigt
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
- [x] Update-Metadatenprüfung verwendet Cache-Buster und `Cache-Control: no-cache`, damit neu veröffentlichte Development-Builds nicht durch einen alten Raw-GitHub/CDN-Treffer verdeckt werden
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
- [x] direkte TMDB-YouTube-Trailer intern in eigener Launcher-Activity mit Bild und Ton auf TV-Hardware bestätigt

**Phase 4 ist funktional abgeschlossen; interne Trailerwiedergabe ist auf TV-Hardware bestätigt.**

## Phase 5 – Gigablue / OpenWebif

- [x] direkte OpenWebif-Verbindung mit validierter lokaler HTTP/HTTPS-Receiver-Adresse
- [x] optionale HTTP-Basic-Authentifizierung; Zugangsdaten bleiben lokal und werden nicht geloggt
- [x] Bouquets über `/api/getservices` einlesen und auswählbar machen
- [x] Sender des gewählten Bouquets in Receiver-Reihenfolge einlesen; Marker ausfiltern
- [x] Picons über OpenWebif auflösen
- [x] EPG Now/Next über `/api/epgnownext` lesen und sendergenau zuordnen
- [x] Local-First-Snapshot für Bouquet/Sender/Now-Next; Netzwerk aktualisiert nach Start und periodisch
- [x] eigene D-Pad-bedienbare `Live TV`-Ansicht für Konfiguration, Bouquetwahl und Diagnose
- [x] `Jetzt im TV` auf Home mit Senderlogo, aktueller Sendung, Zeit, Fortschritt und nächster Sendung
- [x] Unit-Tests für URL-Normalisierung, Bouquet-Reihenfolge, EPG-Zuordnung und Fortschritt
- [x] signierten Phase-5-Development-Build `0.1.0-dev.51` mit Unit-Tests und `assembleDebug` veröffentlicht
- [x] realer TCL + Gigablue-X3-Gerätetest von Verbindung, Bouquet, Sendern, Picons, Now/Next, Home-Reihe und Offline-Cache gemeinsam mit Phase 6 bestanden

**Phase 5 ist funktional abgeschlossen und auf realer TCL-/Gigablue-Hardware bestätigt.**

## Phase 6 – EPG

- [x] externe M3U als reine EPG-Metadatenquelle anbinden; Wiedergabe-/IPTV-URLs nicht verwenden oder persistieren
- [x] `x-tvg-url`, `tvg-id`, `tvg-id-ALT`, `tvg-name`, Logos und Enigma2-Service-Reference-Hinweise parsen
- [x] Gigablue `serviceReference` bevorzugt direkt gegen M3U-Hinweise mappen
- [x] konservatives Sendernamen-Matching als Fallback; unklare Treffer nicht automatisch übernehmen
- [x] manuelle Sender-zu-XMLTV-ID-Zuordnung mit lokal persistiertem Mapping
- [x] alternative XMLTV-IDs automatisch berücksichtigen, wenn die primäre ID keine Programmdaten liefert
- [x] GZIP/XMLTV streamend verarbeiten; keine komplette XML-Datei als String im RAM halten
- [x] XML-Parser gegen externe Entities/DTD-Nachladen absichern
- [x] nur gemappte Sender und begrenztes Zeitfenster laden
- [x] vollständigen EPG-Guide je Gigablue-Sender bereitstellen
- [x] XMLTV-Programme und Sender-Mappings in Room cachen
- [x] Room-Migration 2 → 3 ohne Verlust des bestehenden TMDB-Caches implementieren
- [x] Local-First: vorhandenen EPG-Cache vor Netzwerkrefresh verwenden
- [x] OpenWebif Now/Next als Primärzeit/Event beibehalten und XMLTV-Beschreibung/Kategorie/Staffel/Episode/Jahr/Bild ergänzen
- [x] XMLTV als Now/Next-Fallback verwenden, falls OpenWebif für einen gemappten Sender keine EPG-Sendung liefert
- [x] XMLTV/OpenWebif-Merge gegen schwache zeitliche Überschneidungen absichern, damit keine Metadaten benachbarter Sendungen übernommen werden
- [x] neue D-Pad-bedienbare `EPG`-Ansicht mit Sender- und Programmliste
- [x] EPG beim aktuell laufenden Programm positionieren und laufende Sendung mit `JETZT` markieren
- [x] Senderzuordnung mit XMLTV-ID und Match-Methode in der Live-TV-Diagnose sichtbar machen
- [x] `Jetzt im TV` um verfügbares XMLTV/TMDB-Programmartwork erweitern; Picon als Senderidentität erhalten
- [x] Home vertikal scrollbar machen, damit `Weiterschauen` → `Jetzt im TV` → `Apps` per D-Pad erreichbar ist
- [x] vorhandenen konservativen TMDB-Resolver für plausible Film-/Serienprogramme wiederverwenden
- [x] aktuelle Programme progressiv, weitere Guide-Einträge bei Auswahl mit TMDB anreichern
- [x] Unit-Tests für M3U-Parsing, Sender-Mapping, XMLTV-Zeit/Episoden-Parsing, OpenWebif/XMLTV-Merge und EPG-Startposition hinzufügen
- [x] signierten Phase-6-Development-Build mit `testDebugUnitTest` und `assembleDebug` erfolgreich veröffentlichen
- [x] reale XMLTV-Quelle auf TCL gegen Gigablue-Sender getestet
- [x] D-Pad-/Focus-Test auf Home sowie im vollständigen EPG bestanden
- [x] Offline-/Cache-Test und Update/Migration vom bestehenden Build bestanden
- [x] EPG aus der Hauptnavigation entfernt und als Guide direkt im laufenden Live-TV-Player geöffnet
- [x] langes OK als EPG-Direktzugang auf TV-Hardware bestätigt
- [x] EPG-Programme ohne eindeutige XMLTV-Kategorie nicht mehr vor TMDB verwerfen; `Unknown` nutzt Multi-Search mit unveränderter Confidence-Schwelle
- [x] beim Öffnen des Guides das bereits ausgewählte aktuelle/angesprungene Programm sofort zur TMDB-Anreicherung anstoßen
- [ ] EPG→TMDB-Details mit bekannten Serien wie CSI / Two and a Half Men auf TV-Hardware erneut prüfen

**Phase 6 ist funktional abgeschlossen und auf realer TCL-/Gigablue-/XMLTV-Hardware bestätigt. Der korrigierte EPG→TMDB-Detailpfad wartet auf den nächsten TV-Test.**

## Phase 7 – Live TV

- [x] Media3/ExoPlayer 1.10.1 integrieren
- [x] OpenWebif-Stream über receiver-eigenes `/web/stream.m3u?ref=…` auflösen; keinen Stream-Port raten oder persistieren
- [x] internen TV-Player mit D-Pad-bedienbarer Senderliste und Now/Next-Overlay umsetzen
- [x] laufenden Sender über stabile `serviceReference` halten, sodass periodische Datenrefreshes keinen unerwünschten Kanalwechsel auslösen
- [x] Senderliste per OK dauerhaft öffnen; Back schließt zuerst die Liste
- [x] CH+/CH− und Hoch/Runter für Senderwechsel implementieren
- [x] langes OK öffnet den EPG direkt im Player
- [x] Player-Rückkehr und Verlassen-Dialog auf TV-Hardware getestet

## Phase 8 – Preview Channels / globale Suche

- [x] Android Preview Channels/Programs über TvProvider einlesen
- [x] Preview Channel Reihen auf Home
- [x] lokale Sichtbarkeit je Kanal
- [x] Local-First globale Suche
- [x] TMDB Browse/Discovery
- [x] TMDB Details / Personen / Related Content
- [x] Kodi TMDb Helper Handoff
- [x] CloudStream bleibt separates Provider-/Playback-Backend
- [x] versionierter `cloudstreamplay://v1`-Handoff mit strukturierter Medienidentität
- [x] deterministische Auswahl der Bridge-fähigen `prerelease.debug`-Variante vor einer parallel installierten offiziellen CloudStream-App
- [x] CloudStream Provider-Priorität + letzter tatsächlich abspielbarer Provider je Medienidentität
- [x] Long-OK Providerwahl und globale Prioritätsreihenfolge
- [x] Direct-Play für Filme und konkrete Episoden über CloudStreams vorhandenen `RepoLinkGenerator`
- [x] reine Serie ohne S/E öffnet die bereits aufgelöste Provider-Detailseite statt Episode 1 zu raten
- [x] Provider-Suchtreffer mit dekorierten sichtbaren Titeln werden nicht mehr vor `load()` verworfen; strenge Identitätsprüfung erfolgt auf der echten `LoadResponse`
- [x] CloudStream-Suchfallback aktualisiert nach Kaltstart die Provider-Repositories und startet die Suche aktiv statt nur den Suchtext einzutragen
- [x] reproduzierbarer Bridge-Build auf CloudStream `a72f9e6` inklusive bestehendem Watch-Next-Fix
- [x] automatisierter Bridge-Build #8 mit JVM-Tests und `assemblePrereleaseDebug` grün
- [ ] TCL: automatischen Direktstart mit mehreren realen Extensions prüfen
- [ ] TCL: Fallback-Suche nach Kaltstart muss automatisch Ergebnisse anzeigen
- [ ] TCL: Provider-Priorität und letzter erfolgreicher Provider prüfen
- [ ] TCL: Back-/Fokus-Rückkehr nach direktem CloudStream-Player prüfen
