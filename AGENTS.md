# AGENTS.md

## Zweck

Diese Datei enthält die verbindlichen Entwicklungsrichtlinien für I Launcher / TV Launcher. Sie ist die aktuelle technische und organisatorische Wahrheit für Arbeiten im Repository.

Vor jeder Entwicklungsaufgabe, Codeänderung, Architekturentscheidung, Fehlerbehebung oder Pull-Request-Arbeit:

1. diese `AGENTS.md` lesen,
2. relevante `README.md`, `ROADMAP.md` und `ARCHITECTURE.md` prüfen,
3. den aktuellen GitHub-Stand verwenden,
4. nicht aus älteren Chat-Inhalten raten,
5. bei Widersprüchen dieser Datei Vorrang geben.

## Projektziel

I Launcher ist ein moderner, schneller, werbefreier Android-TV-Launcher als primäre TV-Oberfläche.

Grundprinzip: **Content Launcher statt bloßer App Launcher.**

Der Benutzer soll primär Inhalte auswählen und erst sekundär die App beziehungsweise Quelle, über die diese wiedergegeben werden.

## Technischer Stack

- Kotlin
- Jetpack Compose / Compose for TV
- AndroidX
- Coroutines / Flow
- Room
- Hilt
- Retrofit / OkHttp
- Coil
- Media3

Keine Flutter-Basis.

## Kernfunktionen

- Android-TV-Launcher / Home-App
- installierte Apps
- Android Watch Next / „Weiterschauen“
- Preview Channels aus Apps
- TMDB-Anreicherung für Filme, Serien und Episoden
- Poster, Backdrops, Logos und Episodenbilder
- Trailer vorzugsweise über TMDB + YouTube
- Gigablue X3 direkt über Enigma2/OpenWebif
- Bouquets, Sender, EPG und Streams
- EPG-Anreicherung über TMDB
- integrierter Live-TV-Player mit Media3
- globale Local-First-Suche
- einfache Google-TV-inspirierte UI ohne Werbung

## Watch Next

Watch Next funktioniert auf dem Zielgerät bereits mit Arc Launcher und liefert unter anderem Einträge von CloudStream.

Deshalb zuerst die reguläre Android-TV-/TvProvider-Schnittstelle verwenden.

Keine app-spezifische CloudStream-Integration für Watch Next entwickeln, solange Android bereits die benötigten Daten liefert.

Die Reihenfolge der Watch-Next-Einträge nicht ohne klaren Grund verändern.

Kurzes OK startet den vorhandenen Source-/Playback-Intent. `INFO` beziehungsweise langes OK darf Details öffnen. Long-Press-/Key-Up-Verhalten muss D-Pad-sicher sein und darf beim Übergang zur Detailseite keine unbeabsichtigte Aktion auslösen.

## Preview Channels

Preview Channels ebenfalls zuerst über Android/TvProvider integrieren. Provider-Reihenfolge nicht willkürlich umsortieren. Quellen können lokal ein-/ausblendbar sein, ohne die Providerdaten selbst umzuschreiben.

## Gigablue

Gigablue X3 möglichst direkt über OpenWebif integrieren.

Keine Abhängigkeit von dreamTV, TiviMate oder ähnlichen Apps, sofern OpenWebif die benötigte Funktion direkt bereitstellt.

Senderidentität muss über die stabile Enigma2-`serviceReference` geführt werden. Periodische Metadaten-/EPG-Refreshes dürfen die aktuelle Wiedergabe nicht auf einen alten Listenindex beziehungsweise den ursprünglich geöffneten Sender zurücksetzen.

Stream-URLs nicht unnötig dauerhaft speichern oder loggen.

## EPG

OpenWebif und XMLTV dürfen kombiniert werden. Sender-Mapping lokal und nachvollziehbar halten. EPG-Programme für UI-Auswahl über stabile Identität wie `serviceReference + startUtcMillis` behandeln, damit asynchrone Anreicherung keine Auswahl verliert.

TMDB-Anreicherung nur bei hinreichend eindeutiger Zuordnung übernehmen. Negative beziehungsweise unsichere Mappings müssen nach Resolver-Änderungen erneut prüfbar sein.

## TMDB

TMDB dient der Metadatenanreicherung und Discovery. Token/Credentials nicht in Logs oder Benutzeroberfläche ausgeben.

Local First bleibt auch mit TMDB erhalten:

- lokale Watch-Next-/Preview-/EPG-/App-Daten zuerst,
- TMDB ergänzt Suchergebnisse und Metadaten,
- leere Suche darf TMDB-Browse-/Trend-/Genre-Reihen zeigen,
- Home nicht ohne bewusste Produktentscheidung in einen automatisch rotierenden Netzwerkempfehlungs-Feed verwandeln.

## Trailer

Trailer vorzugsweise über TMDB-Videos auflösen und konkrete YouTube-ID verwenden. Ohne konkrete ID darf YouTube-Suche Fallback sein.

Keine YouTube-Stream-Extraktion beziehungsweise Umgehung der vorgesehenen Wiedergabemechanismen entwickeln.

## Suche

Die globale Suche bleibt Local First. Lokale Quellen nicht in eine undifferenzierte Rankingliste mischen, wenn dadurch Herkunft, erwartete Aktion oder bestehende Reihenfolge verloren geht.

Google TV darf als visuelle/informationelle Referenz dienen: breite ruhige Suchfläche, klarer Voice-Zugang, kompakte Filter/Pills, horizontale Content-Rails und eindeutiger D-Pad-Fokus. Beispielanfragen dürfen nur denselben normalen Suchpfad auslösen und keine versteckte zweite Recommendation-/Search-Engine einführen.

## Google-TV-inspirierte UI

Google TV ist Referenz für Informationshierarchie, Proportionen, Fokusruhe, Rail-Dichte und TV-Lesbarkeit. Keine Pixel-für-Pixel-Kopie und keine Übernahme von Werbung, Sponsored Content oder unnötig dominanten Recommendation-Carousels.

Home soll ruhig, schnell und lokal wirken. Fokuszustände dürfen nicht unnötig große Flächen animieren. Bilder/Artwork bevorzugt als Fokusfläche behandeln; Titel und Sekundärtext können ruhig außerhalb liegen, sofern D-Pad- und Accessibility-Verhalten korrekt bleiben.

## D-Pad / Fernbedienung

D-Pad ist die primäre Bedienform.

Für jeden TV-Screen prüfen:

- Up/Down/Left/Right vorhersehbar,
- keine Focus-Traps,
- keine übersprungenen Kernaktionen,
- Back deterministisch,
- langes OK und Key-Up sauber behandelt,
- Fokus bleibt beim asynchronen Datenrefresh stabil,
- Scrollen zeigt keine abgeschnittenen/stehengebliebenen Kartenreste.

Touch ist nur sekundärer Smoke-Test und darf die TV-Fokuslogik nicht ersetzen.

## Entwicklungsregeln

Nicht raten, wenn Verhalten durch Quellcode, Android-Dokumentation, API-Antworten, Emulator oder Logs überprüfbar ist.

Bei Fehlern:

1. Ursache analysieren,
2. relevanten Code prüfen,
3. erst dann ändern,
4. Build und relevante Tests durchführen,
5. Regressionen prüfen.

Keine zufälligen Workarounds.

Bestehende funktionierende Bereiche nicht unnötig umbauen.

Architektur modular und langfristig wartbar halten.

## Visuelle TV-Prüfung

Bei reproduzierbaren UI-/Layout-/Fokusänderungen nicht nur kompilieren. Soweit technisch möglich den vorhandenen deterministischen Android-TV-Visual-Smoke verwenden oder sinnvoll erweitern.

Der aktuelle Visual-Smoke läuft auf einem 1920×1080 Android-TV-Emulator und darf debug-only Fixture-Daten verwenden, damit Layout, Dichte, Clipping und Fokus reproduzierbar sind.

Für relevante visuelle Änderungen gilt:

1. UI ändern,
2. Debug-Build erstellen,
3. 1080p-Screenshot(s) des betroffenen Zustands erzeugen,
4. Screenshot tatsächlich visuell prüfen,
5. sichtbare Abweichungen korrigieren,
6. danach erneut Screenshot/Build prüfen.

Ein Fixture-Screenshot prüft Geometrie/Fokus, aber nicht reale TMDB-/Provider-Artworkqualität und ersetzt keinen TCL-Gerätetest.

## GitHub-Workflow

Bei größeren Änderungen:

- bestehenden Code analysieren,
- Feature/Fix implementieren,
- Build durchführen,
- Tests ausführen,
- Regressionen prüfen,
- verständliche Commits,
- Pull Request mit nachvollziehbarer Beschreibung.

Eine Funktion gilt erst als fertig, wenn der Build erfolgreich ist und relevante Tests bestanden wurden.

Für UI-Funktionen, die reproduzierbar im Emulator prüfbar sind, gehört der visuelle Smoke-Test zur relevanten Validierung.

Bei Funktionen, die nur auf realer TV-Hardware geprüft werden können:

- Debug-APK erzeugen,
- erforderliche Diagnose/Logging bereitstellen,
- konkreten Gerätetest definieren,
- Testergebnis anschließend auswerten und nachbessern.

Keine APK als getestet bezeichnen, wenn lediglich kompiliert oder nur im Emulator geprüft wurde.

## Development-APK / Signing

Development-APKs müssen update-kompatibel mit dem stabilen Development-Signing-Key erzeugt werden, sofern die Signing-Secrets verfügbar sind. Secrets niemals in Repository-Dateien, Logs, PR-Beschreibungen oder Chat-Antworten kopieren.

Der Publisher soll Unit-Tests und Debug-Build durchführen. Das Update-Manifest muss Version, SHA256, Signing-/Update-Kompatibilität, TMDB-Konfiguration und Source-SHA nachvollziehbar enthalten.

## Datenschutz / Logs

Local First bevorzugen.

Nicht loggen:

- Tokens
- Passwörter
- Receiver-Credentials
- vollständige private Stream-URLs, wenn nicht zwingend erforderlich
- Signing-Secrets

Diagnoselogs sollen technische Zustände enthalten, aber sensible Daten redigieren.

## Architekturentscheidungen

Neue App-spezifische Adapter nur einführen, wenn reguläre Android-/Provider-/Deep-Link-Schnittstellen nicht ausreichen und der Nutzen klar ist.

CloudStream: keine Sonderintegration für Watch Next, solange TvProvider reicht. Ein offizieller Such-/Handoff-Intent darf für explizite Details-/Search-Aktionen verwendet werden.

Kodi: keine instabile Android-Such-Activity erzwingen, wenn Quellcode/Verhalten zeigt, dass der erwartete Providerpfad nicht funktioniert. Exportierte, nachvollziehbare Provider-/Intent-Schnittstellen bevorzugen und nur starke Treffer automatisch öffnen.

## Prioritäten

1. zuverlässige Android-TV-Funktion
2. hervorragende D-Pad-/Fernbedienungsbedienung
3. Performance
4. Wartbarkeit
5. saubere Architektur
6. Local First / Datenschutz
7. möglichst geringe Drittanbieterabhängigkeit
8. Optik

## Definition of Done

Softwareseitig prüfbar:

- Code analysiert und nachvollziehbar geändert,
- relevante Unit-Tests erfolgreich,
- Debug-Build erfolgreich,
- bei reproduzierbarer TV-UI: Visual-Smoke-Screenshot tatsächlich geprüft,
- keine offensichtliche Regression im betroffenen Bereich.

Hardware-/OEM-spezifisch:

- Debug-APK erzeugt,
- konkreter Gerätetest definiert,
- erst nach realer Prüfung als Hardware-getestet bezeichnen.

Die aktuelle technische Wahrheit steht in dieser Datei sowie ergänzend in `README.md`, `ROADMAP.md` und `ARCHITECTURE.md`.
