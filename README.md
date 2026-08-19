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

Die globale TMDB-Suche gewichtet Titelrelevanz und Bekanntheit gemeinsam. Exakte bzw. starke Titelpräfixe bleiben das wichtigste Signal; `vote_count` und `popularity` können jedoch klar etablierte Filme/Serien vor obskuren Namenskollisionen ziehen. Wortpräfixe nach führenden Wörtern werden ebenfalls als starke Treffer behandelt. Regressionstests decken unter anderem die real beobachteten Suchfälle `matrix`, `avatar` und `expend` ab.

Der aktuelle Such-Ranking-Stand `8d5adb58b680782289e13ab512cbf653d6c30714` wurde mit Android CI **#783** (`testDebugUnitTest` + `assembleDebug`) erfolgreich validiert. Signierter Testbuild ist **`0.1.0-dev.420` (`26000420`)** mit `updateCompatible=true` und `tmdbConfigured=true`. Die reale Ergebnisreihenfolge bleibt auf TCL-Hardware mit `matrix`, `avatar` und `expend` zu bestätigen.

Die aus der Google-TV-Launcher-Analyse abgeleiteten Home-/Keyline-/Navigation-/Glow-Prinzipien sind dauerhaft in [`docs/reference/GOOGLE_TV_HOME_CONCEPT.md`](docs/reference/GOOGLE_TV_HOME_CONCEPT.md) dokumentiert.

Ausführliche Regeln und Architektur: [`AGENTS.md`](AGENTS.md), [`ROADMAP.md`](ROADMAP.md), [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Stack

Kotlin · Jetpack Compose · Compose for TV · AndroidX · Coroutines/Flow · Room · Hilt · Retrofit/OkHttp · Coil · Media3

## Lizenz

MIT