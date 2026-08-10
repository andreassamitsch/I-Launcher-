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
- Hero vermeidet doppelte Medienidentität: bei vorhandenem Titellogo wird der gleiche Titel nicht noch einmal als große Überschrift gezeigt; Quell-App-Namen wie CloudStream werden im Medien-Hero nicht wiederholt
- Hero zeigt gegenüber der fokussierten Karte ergänzende Metadaten statt Fortschritt/Karteninhalt zu duplizieren; längere Beschreibungen laufen nach einer Lesepause langsam durch einen begrenzten Textbereich
- kompakte Hauptnavigation `Home · Suche · Apps · Einstellungen`; aktiver Bereich nur per Rahmen. Die Navigation wird fokusgetrieben ausgeblendet, sobald der Nutzer in eine Inhaltsreihe geht, und erst beim Fokus auf den oberen Hero wieder eingeblendet
- Home nutzt den TV-Bildschirm deutlich vollständiger mit kleinen Safe-Area-Rändern; Hero und Karten sind kompakter, Fokus-Skalierung erhält eigenen Innenraum und schneidet Karten nicht mehr an den Reihenrändern ab
- Home-Reihen sind persistent sortierbar. Langes OK auf `Home` öffnet die versteckte Home-Anpassung mit Reihenfolge, Watch-Next-Quellen, App-Kanälen und Reset-Funktionen
- Home besitzt eine eigene App-Reihe mit App-Symbolen. Langes OK auf einer App aktiviert den Verschiebemodus; links/rechts verschiebt die App, OK beendet den Modus. Die Reihenfolge wird lokal gespeichert
- Preview-Channel-Reihen zeigen keinen zusätzlichen Quell-Untertitel mehr. Wenn die Quell-App installiert ist, steht ihr App-Symbol kompakt links neben der Hauptüberschrift
- Live-TV-/Gigablue-Konfiguration als Unterpunkt der Einstellungen und Update-Schnellaktion ganz oben
- EPG als TV-Guide direkt im laufenden Live-TV-Player statt eigener Hauptnavigation; lange Programmbeschreibungen sind per D-Pad scrollbar
- Live-TV-Overlay blendet nach drei Sekunden aus; bei sichtbarem Overlay gehören Hoch/Runter der UI-Navigation, bei ausgeblendetem Overlay dienen Hoch/Runter zum Zappen; CH+/CH− bleiben Senderwechsel
- langes OK im Live-TV-Player öffnet direkt den EPG; der EPG-Button ist aus der Senderreihe per D-Pad erreichbar
- Verlassen des Live-TV-Players erfordert eine Bestätigung. Im Dialog steht `TV verlassen` vor `Abbrechen` und ist standardmäßig fokussiert
- EPG-TMDB-Anreicherung bleibt nicht mehr an fehlenden/unklaren XMLTV-Kategorien hängen: nicht typisierte Programme laufen über TMDB-Multi-Search und müssen weiterhin die bestehende strenge Confidence-Schwelle erfüllen
- der EPG verfolgt das ausgewählte Programm über Senderreferenz und Startzeit statt über die alte Objektinstanz; dadurch kann eine asynchron nachgeladene TMDB-Kopie den `Details`-Button zuverlässig einblenden
- alte negative TMDB-Zuordnungen aus einer früheren Resolver-Policy werden erneut geprüft; neue negative Treffer bleiben nur kurz gecacht. Damit können zuvor fälschlich ungelöste Titel wie `ZeroZeroZero` nach Resolver-Verbesserungen wieder aufgelöst werden
- direkte TMDB-/YouTube-Trailer starten in einer eigenen hardwarebeschleunigten Trailer-Activity; deutsche TMDB-Videos werden bevorzugt, YouTube-UI/Untertitelpräferenz ist Deutsch
- globale Suche zeigt lokale Quellen und TMDB nicht mehr als eine lange gemischte Liste, sondern als TV-Reihen `Weiterschauen`, `App-Kanäle`, `TV-Programm`, `Apps` und `Filme & Serien`
- bei leerer Suche zeigt I Launcher TMDB-Browse-Reihen: `Serien im Trend`, `Filme im Trend`, `Top Science-Fiction-Serien` und `Top Science-Fiction-Filme`. Diese Online-Reihen werden nur im Suchbereich geladen und eine Stunde lokal gecacht
- lokale Suche berücksichtigt unveränderte Android-Watch-Next-Quelltitel sowie Preview-Channel-/Quellnamen zusätzlich zu angereicherten Medienfeldern; TMDB-Suchergebnisse werden bei identischer TMDB-Identität wieder mit lokalen Quellen verknüpft
- Android-Sprachsuche über einen kompakten Mikrofon-Button übernimmt erkannte Sprache in dieselbe Suchpipeline
- Detailseiten fokussieren beim Öffnen direkt die erste sinnvolle Aktion; Watch-Next-Details werden nach langem OK erst nach konsumiertem OK-Release geöffnet, damit der frisch fokussierte Wiedergabe-Button nicht unbeabsichtigt ausgelöst wird
- Suchziele erscheinen als Suchsymbol plus App-Name; das Symbol übernimmt wie der Text die aktuelle TV-Material-Fokusfarbe
- CloudStream-Suchhandoff erkennt Stable-, Prerelease-, Debug- und kombinierte Development-Paketvarianten dynamisch über den `cloudstreamsearch`-Intent
- Kodi-Core-`ACTION_SEARCH` wird nicht mehr verwendet: I Launcher fragt stattdessen Kodis exportierten Suggestions-Provider ab und öffnet nur einen starken Kodi-Bibliothekstreffer über die von Kodi selbst zurückgegebene Referenz; ohne sicheren Treffer wird Kodi normal geöffnet
- der Kodi-Handoff durchsucht damit nur die Kodi-Core-Bibliothek, keine beliebigen Kodi-Add-ons; Add-on-spezifische Suche bleibt eine eigene spätere Integration und wird nicht geraten
- CloudStreams aktuelle Such-Activity erzwingt beim Öffnen selbst die Soft-Tastatur; die externe `cloudstreamsearch`-Schnittstelle bietet keinen dokumentierten Parameter zum Unterdrücken, daher implementiert I Launcher keinen timing-basierten Back-Workaround
- zusätzlicher Touch-Kompatibilitätslayer für Handy-/Tablet-Smoke-Tests: TV-Material-Buttons und -Cards behalten D-Pad/Fokus, erhalten aber explizite Pointer-Taps und Scroll-Fallbacks; `leanback` ist für die Testinstallation auf Nicht-TV-Geräten optional
- Medien-Detailansicht ist vertikal per Touch scrollbar; lange Beschreibungen und umgebrochene Aktionsbuttons sind auf dem Handy praktisch bestätigt

Die interne Trailerwiedergabe mit Bild und Ton, die EPG-Integration im Live-TV-Player, die neue Suchreihen-Struktur sowie mehrere Player-Aktionen wurden auf realer TV-Hardware bestätigt. Die jüngsten Korrekturen an Home-Sortierung/Vollbildnutzung, EPG-Details, ZeroZeroZero-Negativcache und TMDB-Browse benötigen noch den nächsten TV-Gerätetest.

## Datenschutz / Sicherheit

OpenWebif-Zugangsdaten werden nur lokal gespeichert, nicht geloggt und durch `allowBackup=false` nicht über Android Auto Backup ausgelagert. Externe EPG-Quellen erhalten keine Receiver-Zugangsdaten. Stream-Adressen und temporäre Streaming-Authentifizierung bleiben flüchtig im Arbeitsspeicher.

Watch Next liefert CloudStream-Einträge über die reguläre Android-TvProvider-Schnittstelle. Der optionale Suchhandoff aus TMDB-/EPG-Ergebnissen verwendet nur explizite externe Schnittstellen der Ziel-Apps; I Launcher baut keine CloudStream- oder Kodi-Providerlogik nach.

Siehe [`AGENTS.md`](AGENTS.md), [`ROADMAP.md`](ROADMAP.md) und [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Stack

Kotlin · Jetpack Compose · Compose for TV · AndroidX · Coroutines/Flow · Room · Hilt · Retrofit/OkHttp · Coil · Media3

## Build-Basis

Android Gradle Plugin 9.3.1 · Gradle 9.5.0 (CI) · compileSdk 36 · targetSdk 36 · minSdk 26 · Compose BOM 2026.06.00 · Compose for TV 1.1.0 · Media3 1.10.1 · Coil 3.5.0 · Room 2.8.4 · Retrofit 3.0.0 · OkHttp 5.3.0

## Lizenz

MIT
