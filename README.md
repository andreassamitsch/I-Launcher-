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

- TV-first Hero mit Artwork auf der rechten Seite und weichem Verlauf in den linken Textbereich; echte Backdrops werden flächig genutzt, ungeeignete Quellbilder/Poster möglichst unbeschnitten dargestellt
- Hero startet nicht mehr automatisch mit dem ersten TV-Sender: zunächst wird Local First ein Watch-Next-Inhalt, danach ein Preview-Channel-Inhalt und sonst ein neutraler Launcher-Hero verwendet; erst aktive Fokusbewegung übernimmt den Hero
- Hero zeigt gegenüber der fokussierten Karte ergänzende Metadaten statt Fortschritt/Karteninhalt zu duplizieren; längere Beschreibungen laufen nach kurzer Pause automatisch durch einen begrenzten Textbereich
- kompakte Hauptnavigation `Home · Suche · Apps · Einstellungen`; aktiver Bereich nur per Rahmen; Navigation kann auf Home beim Scrollen der Reihen ausblenden und wird beim Zurücknavigieren zum Hero wieder eingeblendet
- Live-TV-/Gigablue-Konfiguration als Unterpunkt der Einstellungen und Update-Schnellaktion ganz oben
- EPG als TV-Guide direkt im laufenden Live-TV-Player statt eigener Hauptnavigation; lange Programmbeschreibungen sind per D-Pad scrollbar
- Live-TV-Overlay blendet nach drei Sekunden aus; bei sichtbarem Overlay gehören Hoch/Runter der UI-Navigation, bei ausgeblendetem Overlay dienen Hoch/Runter zum Zappen; CH+/CH− bleiben Senderwechsel
- langes OK im Live-TV-Player öffnet direkt den EPG; der EPG-Button ist aus der Senderreihe per D-Pad erreichbar
- Verlassen des Live-TV-Players erfordert nach ausgeblendeter UI bzw. über `TV verlassen` eine Bestätigung, damit Back nicht versehentlich den Stream beendet
- EPG-Sendungen mit TMDB-Referenz können die provider-neutrale Detailseite öffnen; von dort stehen wiederum verfügbare externe Suchziele und Trailer bereit
- direkte TMDB-/YouTube-Trailer starten in einer eigenen hardwarebeschleunigten Trailer-Activity; deutsche TMDB-Videos werden bevorzugt, YouTube-UI/Untertitelpräferenz ist Deutsch
- globale Suche zeigt lokale Quellen und TMDB nicht mehr als eine lange gemischte Liste, sondern als TV-Reihen `Weiterschauen`, `App-Kanäle`, `TV-Programm`, `Apps` und `Filme & Serien`
- lokale Suche berücksichtigt unveränderte Android-Watch-Next-Quelltitel sowie Preview-Channel-/Quellnamen zusätzlich zu angereicherten Medienfeldern; TMDB-Suchergebnisse werden bei identischer TMDB-Identität wieder mit lokalen Quellen verknüpft
- Android-Sprachsuche über einen kompakten Mikrofon-Button übernimmt erkannte Sprache in dieselbe Suchpipeline
- Detailseiten fokussieren beim Öffnen direkt die erste sinnvolle Aktion; bei TMDB-/EPG-Details ist die Reihenfolge verfügbare App-Suche → Trailer → Zurück, Suchziele erscheinen als Suchsymbol plus App-Name
- CloudStream-Suchhandoff erkennt Stable-, Prerelease-, Debug- und kombinierte Development-Paketvarianten dynamisch über den `cloudstreamsearch`-Intent
- Kodi-Suchhandoff verwendet Kodis exportierte `XBMCSearchableActivity` mit `ACTION_SEARCH`, `SearchManager.QUERY` und einer nicht-null Data-URI, da die aktuelle Kodi-Activity `intent.data` vor der Suchverarbeitung dereferenziert
- CloudStreams aktuelle Such-Activity erzwingt beim Öffnen selbst die Soft-Tastatur; die externe `cloudstreamsearch`-Schnittstelle bietet keinen dokumentierten Parameter zum Unterdrücken, daher implementiert I Launcher keinen timing-basierten Back-Workaround
- zusätzlicher Touch-Kompatibilitätslayer für Handy-/Tablet-Smoke-Tests: TV-Material-Buttons und -Cards behalten D-Pad/Fokus, erhalten aber explizite Pointer-Taps und Scroll-Fallbacks; `leanback` ist für die Testinstallation auf Nicht-TV-Geräten optional
- Medien-Detailansicht ist vertikal per Touch scrollbar; lange Beschreibungen und umgebrochene Aktionsbuttons sind auf dem Handy praktisch bestätigt

Die interne Trailerwiedergabe mit Bild und Ton sowie die EPG-Integration im Live-TV-Player wurden auf realer TV-Hardware bestätigt. Die jüngsten Änderungen an Hero, modernisierter Suche, Kodi-Handoff, Detailfokus, Live-TV-D-Pad-/Long-OK-Verhalten, EPG→Details und Exit-Bestätigung sind automatisiert gebaut, benötigen aber noch den nächsten TV-Gerätetest.

## Datenschutz / Sicherheit

OpenWebif-Zugangsdaten werden nur lokal gespeichert, nicht geloggt und durch `allowBackup=false` nicht über Android Auto Backup ausgelagert. Externe EPG-Quellen erhalten keine Receiver-Zugangsdaten. Stream-Adressen und temporäre Streaming-Authentifizierung bleiben flüchtig im Arbeitsspeicher.

Watch Next liefert CloudStream-Einträge über die reguläre Android-TvProvider-Schnittstelle. Der optionale Suchhandoff aus TMDB-/EPG-Ergebnissen verwendet nur externe Suchschnittstellen der Ziel-Apps; I Launcher baut keine CloudStream- oder Kodi-Providerlogik nach.

Siehe [`AGENTS.md`](AGENTS.md), [`ROADMAP.md`](ROADMAP.md) und [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Stack

Kotlin · Jetpack Compose · Compose for TV · AndroidX · Coroutines/Flow · Room · Hilt · Retrofit/OkHttp · Coil · Media3

## Build-Basis

Android Gradle Plugin 9.3.1 · Gradle 9.5.0 (CI) · compileSdk 36 · targetSdk 36 · minSdk 26 · Compose BOM 2026.06.00 · Compose for TV 1.1.0 · Media3 1.10.1 · Coil 3.5.0 · Room 2.8.4 · Retrofit 3.0.0 · OkHttp 5.3.0

## Lizenz

MIT
