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

Der aktuelle Anwendungsstand `c8f93774d8e6ddd706fb01553d7376dc6bbb6256` wurde mit Android CI **#562** und TV Visual Smoke **#171** inklusive der Sequenz `Nav → DOWN erste Rail → UP Nav → DOWN erste Rail`, deterministischen 1920×1080-Screenshots, Deep-Scroll, Foreground-/Crash-Prüfungen und D-Pad-Navigationsvideo validiert. Signierter Testbuild ist **`0.1.0-dev.311` (`26000311`)** mit `updateCompatible=true` und `tmdbConfigured=true`. Die nachfolgenden Branch-Commits dokumentieren diesen Stand und ändern keinen Anwendungscode. Reale D-Pad-/Hero-Layering-Wirkung bleibt auf TCL-Hardware erneut zu bestätigen.

Die aus der Google-TV-Launcher-Analyse abgeleiteten Home-/Keyline-/Navigation-/Glow-Prinzipien sind dauerhaft in [`docs/reference/GOOGLE_TV_HOME_CONCEPT.md`](docs/reference/GOOGLE_TV_HOME_CONCEPT.md) dokumentiert.

Ausführliche Regeln und Architektur: [`AGENTS.md`](AGENTS.md), [`ROADMAP.md`](ROADMAP.md), [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Stack

Kotlin · Jetpack Compose · Compose for TV · AndroidX · Coroutines/Flow · Room · Hilt · Retrofit/OkHttp · Coil · Media3

## Lizenz

MIT
