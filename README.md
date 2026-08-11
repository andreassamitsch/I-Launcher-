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

Der aktuelle Entwicklungsstand enthält den Google-TV-inspirierten Home-/Search-/Player-UX-Pass inklusive edge-to-edge Hero, Hero/Rail-Überlagerung, Top-Crop, dynamischem inhaltsbezogenem Karten-Glow, Breath-Fokusrahmen, konfigurierbarer Watch-Next-Bildwahl, Preview-Channel-TMDB-Opt-in, Live-TV-Focus-Enrichment, robustem TMDB-Jahresfallback und direkter Kodi-TMDb-Helper-Suche.

Signierter Teststand: **`0.1.0-dev.265` (`26000265`)**, `updateCompatible=true`, `tmdbConfigured=true`, App-Source `79e043446a7f264a5227916ffbc790ef61900572`. Android CI **#490** und TV Visual Smoke **#99** sind erfolgreich. Die finalen 1920×1080-Screenshots wurden auf Hero/Rail-Überlagerung, edge-to-edge Darstellung, EPG-Fallback sowie den statischen Focus-Glow/Border-Zustand geprüft. Zeitlicher Breath-Effekt, reales Live-TV-TMDB-Nachladen, neue Home-Optionen, `ZeroZeroZero (2019)` und der Kodi-TMDb-Helper-Handoff benötigen den TCL-Gerätetest.

Ausführliche Regeln und Architektur: [`AGENTS.md`](AGENTS.md), [`ROADMAP.md`](ROADMAP.md), [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Stack

Kotlin · Jetpack Compose · Compose for TV · AndroidX · Coroutines/Flow · Room · Hilt · Retrofit/OkHttp · Coil · Media3

## Lizenz

MIT
