# Roadmap

## Ziel

I Launcher soll ein moderner, schneller, werbefreier Android-TV-Content-Launcher werden. Die Bedienung erfolgt primär mit D-Pad/Fernbedienung. Inhalte stehen vor Apps. Local First und direkte Android-/OpenWebif-Schnittstellen haben Vorrang vor zusätzlichen Drittanbieterabhängigkeiten.

## Phase 1 – Launcher-Grundlage

- [x] Android-TV-Home-App registrieren
- [x] installierte Leanback-Apps anzeigen
- [x] Compose-for-TV-Grundgerüst
- [x] TV-fähige D-Pad-Navigation
- [x] Home / Suche / Apps / Einstellungen
- [x] stabile Application ID
- [x] Development-Build und Updatekanal
- [x] Touch-Smoke-Fallback für Smartphone/Tablet

## Phase 2 – Android-TV-Content

- [x] Watch Next über TvProvider lesen
- [x] Preview Channels lesen
- [x] bestehende Android-Reihenfolge respektieren
- [x] Watch-Next-Quellen lokal ein-/ausblendbar
- [x] Preview-Channels lokal ein-/ausblendbar
- [x] Watch Next: kurzer OK-Handoff
- [x] Watch Next: INFO/langes OK für Details
- [x] Long-OK-Release vor Detailaktion konsumieren
- [x] Home-Reihenfolge lokal speichern
- [x] App-Reihenfolge lokal speichern

## Phase 3 – TMDB

- [x] TMDB-Client und Authentifizierung
- [x] Film-/Serien-/Episoden-Metadaten
- [x] Watch-Next-Anreicherung
- [x] Poster / Backdrops / Logos / Episodenbilder
- [x] lokaler TMDB-Cache
- [x] Trailer-Metadaten aus TMDB
- [x] YouTube-Fallback
- [x] interne Trailer-Wiedergabe bei konkreter YouTube-ID
- [x] TMDB-Discovery/Browse für leere Suche

## Phase 4 – Globale Suche

- [x] lokale Suche über Watch Next
- [x] lokale Suche über Preview Channels
- [x] lokale Suche über EPG
- [x] lokale Suche über installierte Apps
- [x] TMDB als ergänzende Suche
- [x] Local-First-Quellengruppen beibehalten
- [x] Sprachsuche über Android Recognizer
- [x] Google-TV-inspirierte breite Suchfläche und separater Voice-Button
- [x] auswählbare Beispielanfragen bei leerer Suche
- [x] kompakte Ergebnisfilter `Alle / Filme & Serien / TV / Apps`
- [x] kompakte 16:9-Suchergebnis-Rails mit Artwork-only-Fokus
- [x] deterministische 1920×1080 Search-Discover-/Query-/Fokus-Screenshots im Visual-Smoke

## Phase 5 – Gigablue / OpenWebif

- [x] OpenWebif-Verbindung
- [x] Bouquets
- [x] Sender
- [x] Stream-Auflösung
- [x] Live-TV-Player mit Media3
- [x] XMLTV-EPG-Import
- [x] lokales EPG-Caching
- [x] automatisches / manuelles Sender-Mapping
- [x] TMDB-Anreicherung für EPG-Programme
- [x] EPG-Details aus dem Player
- [x] stabile Senderidentität über `serviceReference`
- [x] Metadatenrefresh darf aktiven Sender nicht zurücksetzen
- [x] angeheftete Senderliste bei normalem OK
- [x] Back schließt zuerst nur die Senderliste
- [ ] Langzeittest auf realer Gigablue/TCL-Hardware

## Phase 6 – Home-UX / Google-TV-inspirierte Oberfläche

- [x] ruhiger Local-First-Hero
- [x] kein automatischer Netzwerk-/Werbe-Carousel auf Home
- [x] Backdrop-/Poster-/4:3-sichere Hero-Komposition
- [x] Hero-Text und Rails auf gemeinsame Content-Achse bringen
- [x] Hero-Fade in die Content-Rails
- [x] kompakte ungefähr 172×97dp 16:9-Media-Rails
- [x] Artwork-only-Fokus für Home-Media-Karten
- [x] sekundäre Karteninformationen reduzieren
- [x] kompakter App-Icon-Dock
- [x] adaptive `Alle Apps`-Ansicht
- [x] kleine ruhige Primärnavigation
- [x] partielle Card-Reste beim vertikalen Scrollen maskieren
- [x] deterministischer 1920×1080 Home-Visual-Smoke
- [ ] finale Abnahme von Hero-Proportionen/Artwork auf realem TCL
- [ ] finale Abnahme der Kartenlesbarkeit aus normaler Sitzentfernung

## Phase 7 – App-Handoffs

- [x] CloudStream über offiziellen `cloudstreamsearch://`-Handoff
- [x] Kodi-Suche nicht über defekte Android-`ACTION_SEARCH`-Activity
- [x] Kodi-Suggestions-Provider für starke lokale Bibliothekstreffer
- [x] sicherer Fallback auf normalen Kodi-Start
- [ ] reale Kodi-Bibliothek mit mehr Titeln und Add-ons prüfen
- [ ] weitere App-Handoffs nur bei klar dokumentierter/stabiler Schnittstelle

## Phase 8 – Stabilisierung

- [x] Unit-Test + Debug-Build in CI
- [x] signierter Development-Publisher
- [x] In-App-Updater
- [x] deterministische TV-Visual-Smoke-Screenshots
- [x] Search-Visual-Smoke zusätzlich zu Home
- [ ] D-Pad-Regressionsrunde auf realem TCL
- [ ] Live-TV-Langzeittest mit periodischem Refresh
- [ ] Speicher-/Performance-Profiling auf Zielgerät
- [ ] Offline-/Receiver-Ausfall-Szenarien vollständig testen
- [ ] Release-Härtung und Versionsstrategie für ersten stabilen Release

## Definition of Done

Eine Funktion gilt nicht allein durch Kompilierung als fertig. Für softwareseitig prüfbare Änderungen müssen relevante Unit-Tests und der Debug-Build erfolgreich sein. UI-Geometrie und Fokus werden zusätzlich im TV-Visual-Smoke geprüft, sofern reproduzierbar. Gerät-/OEM-/Tuner-spezifische Funktionen brauchen einen definierten realen Hardwaretest. Eine APK darf nur als auf Hardware getestet bezeichnet werden, wenn dieser Test tatsächlich durchgeführt wurde.
