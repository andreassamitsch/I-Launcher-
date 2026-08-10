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

**Phasen 1 bis 7 sind funktional abgeschlossen und auf dem Zielgerät bestätigt.** Der aktuelle gestapelte Entwicklungsstand ergänzt Preview Channels, globale Suche und die nächste Home-/Player-UX. Diese Änderungen bleiben bis zum jeweils aktuellen Gerätetest in Draft-PRs.

Aktuell umgesetzt:

- fest sichtbarer, fokussierbarer Hero außerhalb des vertikalen Reihen-Scrolls; Fokus auf Watch Next, Live-TV, Preview Channels oder Apps aktualisiert Inhalt/Artwork; Medien-Hero öffnet per OK die Detailansicht
- Hero-Artwork mit zweistufiger Darstellung: dezenter vollflächiger Hintergrund plus unbeschnittenes `Fit`-Motiv, damit auch 4:3-, 16:9- und hochformatige Quellen nicht unnötig abgeschnitten werden
- kompakte Hauptnavigation `Home · Suche · Apps · Einstellungen`; aktiver Bereich nur per Rahmen; Navigation kann auf Home beim Scrollen der Reihen ausblenden und wird beim Zurücknavigieren zum Hero wieder eingeblendet
- Live-TV-/Gigablue-Konfiguration als Unterpunkt der Einstellungen
- Update-Schnellaktion ganz oben in den Einstellungen
- EPG als TV-Guide direkt im laufenden Live-TV-Player statt eigener Hauptnavigation; lange Programmbeschreibungen sind per D-Pad scrollbar
- Live-TV-Infoleisten blenden nach drei Sekunden aus; OK zeigt sie, erstes Zurück versteckt sie
- kompakte `Jetzt im TV`-Senderreihe im Player zum direkten Zappen; D-Pad hoch erhöht, D-Pad runter verringert die Sendernummer
- direkte TMDB-/YouTube-Trailer starten in einer eigenen hardwarebeschleunigten Trailer-Activity; deutsche TMDB-Videos werden bevorzugt, YouTube-UI/Untertitelpräferenz ist Deutsch; externe YouTube-Suche bleibt Fallback ohne konkrete Trailer-ID
- bestehende TMDB-Trailer-Caches aus älteren Development-Builds werden einmalig neu aufgelöst, damit die neue deutsche Trailerpräferenz auch bei bereits bekannten Filmen/Serien wirksam wird
- lokale Suche berücksichtigt unveränderte Android-Watch-Next-Quelltitel sowie Preview-Channel-/Quellnamen zusätzlich zu angereicherten Medienfeldern
- TMDB-Suchergebnisse werden bei identischer TMDB-Identität wieder mit vorhandenen Watch-Next-/Preview-Quellen verknüpft; source-backed Watch-Next-Treffer öffnen Details mit `Fortsetzen`
- Suchergebnisliste springt bei einer neuen Suchanfrage wieder an den Anfang, damit lokale Treffer nicht unterhalb eines zuvor sichtbaren TMDB-Blocks verborgen bleiben
- Android-Sprachsuche über einen kompakten Mikrofon-Button übernimmt erkannte Sprache in dieselbe Suchpipeline
- CloudStream-Suchhandoff erkennt Stable-, Prerelease-, Debug- und kombinierte Development-Paketvarianten dynamisch über den `cloudstreamsearch`-Intent
- zusätzlicher Touch-Kompatibilitätslayer für Handy-/Tablet-Smoke-Tests: TV-Material-Buttons und -Cards behalten D-Pad/Fokus, erhalten aber explizite Pointer-Taps; Home, Suche, Apps, Einstellungen, EPG und Live-TV-Listen erhalten Touch-Scroll-Fallbacks; `leanback` ist für die Testinstallation auf Nicht-TV-Geräten optional
- Medien-Detailansicht ist nun ebenfalls vertikal per Touch scrollbar; lange Beschreibungen werden vollständig angezeigt und die Aktionsbuttons umbrechen bei schmalen Displays statt außerhalb des sichtbaren Bereichs zu liegen

Die interne Trailerwiedergabe mit Bild und Ton sowie die EPG-Integration im Live-TV-Player wurden auf realer TV-Hardware bestätigt. Die danach ergänzten Änderungen an Hero-Darstellung, Menü-Rückkehr, Suche, Sprachsuche, EPG-Textscroll, kompakter Zapping-Reihe, Update-Schnellaktion und Touch-Kompatibilität benötigen noch den nächsten Geräte-/Touchtest.

## Datenschutz / Sicherheit

OpenWebif-Zugangsdaten werden nur lokal gespeichert, nicht geloggt und durch `allowBackup=false` nicht über Android Auto Backup ausgelagert. Externe EPG-Quellen erhalten keine Receiver-Zugangsdaten. Stream-Adressen und temporäre Streaming-Authentifizierung bleiben flüchtig im Arbeitsspeicher.

Watch Next liefert CloudStream-Einträge über die reguläre Android-TvProvider-Schnittstelle. Der optionale Suchhandoff aus TMDB-Ergebnissen verwendet nur die von CloudStream bereitgestellte externe Suchschnittstelle; I Launcher baut keine CloudStream-Provider nach.

Siehe [`AGENTS.md`](AGENTS.md), [`ROADMAP.md`](ROADMAP.md) und [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Stack

Kotlin · Jetpack Compose · Compose for TV · AndroidX · Coroutines/Flow · Room · Hilt · Retrofit/OkHttp · Coil · Media3

## Build-Basis

Android Gradle Plugin 9.3.1 · Gradle 9.5.0 (CI) · compileSdk 36 · targetSdk 36 · minSdk 26 · Compose BOM 2026.06.00 · Compose for TV 1.1.0 · Media3 1.10.1 · Coil 3.5.0 · Room 2.8.4 · Retrofit 3.0.0 · OkHttp 5.3.0

## Lizenz

MIT
