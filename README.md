# I Launcher

Android-TV-Launcher / Home-App in Kotlin und Jetpack Compose for TV.

## Ziel

I Launcher soll ein schneller, moderner und werbefreier **Content Launcher** für Android TV werden. Inhalte stehen vor Apps. Die Oberfläche orientiert sich an ruhigen Google-TV-Prinzipien, ohne Werbung oder automatisch rotierende Netzwerkempfehlungen.

## Aktueller Stand

- echte `CATEGORY_HOME`-Launcher-Activity
- TV-/Leanback-App-Liste
- Watch Next über Android `TvProvider`
- Preview Channels / Programs über `TvProvider`
- TMDB-Anreicherung von Watch Next und App-/EPG-Inhalten
- Home-Hero mit Backdrop/Logo, Beschreibung und Detail-/Öffnen-Handoff
- kompakte 16:9-Content-Rails für Watch Next, App-Kanäle und Live TV
- kompakte App-Icon-Reihe mit lokal gespeicherter Reihenfolge
- adaptive `Alle Apps`-Ansicht
- direkte Gigablue-/OpenWebif-Anbindung
- XMLTV-/EPG-Cache und TMDB-Anreicherung
- integrierter Media3-Live-TV-Player
- globale Local-First-Suche über Watch Next, App-Kanäle, EPG, Apps und ergänzend TMDB
- Browse-/Discover-Zustand bei leerer Suche
- Google-TV-inspirierte Suchoberfläche mit breiter Suchfläche, Voice-Button, Beispielanfragen, Filtern und kompakten Ergebnis-Rails
- interne Trailer-Wiedergabe bei konkreter TMDB-/YouTube-ID, sonst YouTube-Fallback
- Kodi- und CloudStream-Handoffs
- signierter Development-Updatekanal
- deterministischer 1920×1080 Android-TV-Visual-Smoke für Home und Suche

## Suche

Bei leerer Suche zeigt I Launcher eine breite TV-Suchfläche mit separater Sprachsuche, auswählbaren Beispielanfragen und anschließend TMDB-Browse-Reihen.

Bei einer expliziten Suche bleiben lokale Quellen getrennt und vorrangig:

- `Weiterschauen`
- `Aus deinen Apps`
- `Im TV`
- `Apps`
- ergänzend `Filme & Serien` aus TMDB

Die Filter `Alle`, `Filme & Serien`, `TV` und `Apps` verändern nur die sichtbaren Ergebnisgruppen, nicht Backend-Suche oder Quellenreihenfolge. Suchkarten nutzen dieselbe kompakte 16:9-Sprache wie Home; nur das Artwork ist fokussierbar, Titel und Sekundärtext bleiben ruhig darunter.

## Visual Smoke

Der Workflow `TV Visual Smoke` startet einen API-34-Android-TV-Emulator mit 1920×1080 und rendert deterministische Debug-Fixtures ohne Netzwerk-, TvProvider- oder OpenWebif-Abhängigkeit.

Er prüft unter anderem:

- Home initial
- Home mit Fokus in Content-Rails
- tiefer Home-Scroll
- Search Discover
- Search Query
- Fokus auf einem Suchtreffer

Der Emulator-Smoke prüft Geometrie, Clipping und Fokus. Er ersetzt keinen realen TCL-/Gigablue-Hardwaretest.

## Entwicklungsrichtlinien

Die verbindlichen Richtlinien stehen in [`AGENTS.md`](AGENTS.md). Architekturdetails stehen in [`ARCHITECTURE.md`](ARCHITECTURE.md), geplante Schritte in [`ROADMAP.md`](ROADMAP.md).
