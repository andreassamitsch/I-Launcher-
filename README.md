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
- integrierter Live-TV-Player mit Media3
- vollständige D-Pad-/Fernbedienungsbedienung
- Local First

## Status

Der aktuelle Entwicklungsstand enthält den Google-TV-inspirierten Home-/Search-/Player-UX-Pass inklusive edge-to-edge Hero, Hero/Rail-Überlagerung, Top-Crop, stabiler Row-Keyline, dynamischem artwork-farbigem Karten-Glow, Breath-Fokusrahmen und Google-TV-artiger Overlay-Navigation. Auf Home blendet die Top-Navigation beim Wechsel in die Content-Rails ohne Layout-Reflow zu einem kleinen Chevron aus. Der Rückweg aus der obersten Rail zur Navigation ist explizit verdrahtet, damit die großflächige Hero-Fokusfläche die D-Pad-Navigation auf realer TV-Hardware nicht abfangen kann. Der Hero bleibt außerdem auch im Fokuszustand unter den überlappenden Rails. Dazu kommen konfigurierbare Watch-Next-Bildwahl, Preview-Channel-TMDB-Opt-in, Live-TV-Focus-Enrichment, robuster TMDB-Jahres-/Typ-Fallback und direkte Kodi-TMDb-Helper-Suche.

Die TMDB-Suche verwendet jetzt ein kombiniertes Ranking aus Titelrelevanz und gedeckelter Bekanntheit (`vote_count` / `popularity`). Dadurch bleiben exakte bzw. starke Titelmatches dominant, während etablierte Filme und Serien obskure Namenskollisionen sinnvoll überholen können. Der reale TCL-Test mit dem signierten Build `0.1.0-dev.420` wurde vom Benutzer als gut funktionierend bestätigt.

Die aktuelle Filme-/Serien-Discovery besitzt zusätzlich Google-TV-artige „Mehr“-Unterseiten, globale Anime-/Kindermodus-Filter und launcher-eigene Genre-Relevanz. Film-Discover und Kino-Feeds sind auf den deutschen Markt (`region=DE`) ausgerichtet, während `language=de-DE` weiterhin die Metadaten lokalisiert. Comedy-Discovery verwirft klar sekundäre Superhelden-/Action-Adventure- sowie Crime-Thriller-Komödien, und Unterseiten tragen die jeweilige Ausgangskategorie in jeder Reihenüberschrift.

Die aus der Google-TV-Launcher-Analyse abgeleiteten Home-/Keyline-/Navigation-/Glow-Prinzipien sind dauerhaft in [`docs/reference/GOOGLE_TV_HOME_CONCEPT.md`](docs/reference/GOOGLE_TV_HOME_CONCEPT.md) dokumentiert.

Ausführliche Regeln und Architektur: [`AGENTS.md`](AGENTS.md), [`ROADMAP.md`](ROADMAP.md), [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Stack

Kotlin · Jetpack Compose · Compose for TV · AndroidX · Coroutines/Flow · Room · Hilt · Retrofit/OkHttp · Coil · Media3

## Lizenz

MIT
