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

**Phasen 1 bis 7 sind funktional abgeschlossen und auf dem Zielgerät bestätigt.** Der aktuelle gestapelte Entwicklungsstand ergänzt Preview Channels, globale Suche und einen Google-TV-inspirierten Home-/Search-/Player-UX-Pass. Diese Änderungen bleiben bis zum jeweils aktuellen Gerätetest in Draft-PRs.

Aktuell umgesetzt:

- TV-first Hero als cinematische Vollbreitenfläche: echte Backdrops füllen den Hero; ungeeignete Quellbilder/Poster werden über einen dezenten Crop-Hintergrund plus möglichst unbeschnittenes `Fit`-Motiv rechts dargestellt
- mehrstufige horizontale und vertikale Verläufe lassen das Artwork weich in den linken Textbereich und nach unten in die Content-Reihen auslaufen; Hero-Wechsel blenden weich über statt hart zu springen
- Hero startet nicht mit dem ersten TV-Sender: zunächst wird Local First ein Watch-Next-Inhalt, danach ein Preview-Channel-Inhalt und sonst ein neutraler Launcher-Hero verwendet; ein automatisch rotierendes Netzwerk-/Werbekarussell gibt es bewusst nicht
- Hero vermeidet doppelte Medienidentität: bei vorhandenem Titellogo wird derselbe Titel nicht noch einmal als große Überschrift gezeigt; Quell-App-Namen wie CloudStream werden im Medien-Hero nicht wiederholt
- Hero zeigt kompakte ergänzende Metadaten statt Kartenwerte zu duplizieren; längere Beschreibungen starten erst nach einer Lesepause und scrollen bewusst langsam
- Home-Medienreihen verwenden ein einheitlicheres 16:9-Raster, kleinere Fokus-Skalierung, ruhigere Überschriften und kompaktere Abstände nach Vorbild moderner TV-Oberflächen
- Home nutzt den TV-Bildschirm mit kleinen Safe-Area-Rändern; Fokus-Skalierung erhält eigenen Innenraum und soll Karten nicht an Reihenrändern abschneiden
- Home-Reihen sind persistent sortierbar. Langes OK auf `Home` öffnet die versteckte Home-Anpassung mit Reihenfolge, Watch-Next-Quellen, App-Kanälen und Reset-Funktionen
- eigene Home-Reihe `Meine Apps` mit großen App-Icons. Langes OK auf einer App aktiviert den Verschiebemodus; links/rechts verschiebt die App, OK beendet den Modus; die Reihenfolge wird lokal gespeichert
- Preview-Channel-Reihen zeigen keinen zusätzlichen Quell-Untertitel. Wenn die Quell-App installiert ist, steht ihr App-Symbol kompakt links neben der Hauptüberschrift
- kompakte Hauptnavigation `Home · Suche · Apps · Einstellungen`; aktiver Bereich bleibt per Rahmen markiert, in Ruhe sind die Flächen transparent und der tatsächliche Fokus erscheint als helle kompakte Pill mit nur kleiner Vergrößerung. Die bestehende D-Pad-Reihenfolge wurde dabei nicht verändert
- die Navigation wird fokusgetrieben ausgeblendet, sobald der Nutzer in eine Inhaltsreihe geht, und erst beim Fokus auf den oberen Hero wieder eingeblendet
- die separate Ansicht `Alle Apps` verwendet ein adaptives TV-Grid, damit die verfügbare Breite sinnvoller genutzt wird; dort bleiben App-Namen dauerhaft sichtbar, während das reduzierte Home-App-Dock Labels weiterhin nur bei Fokus oder Verschiebemodus zeigt
- Live-TV-/Gigablue-Konfiguration als Unterpunkt der Einstellungen und Update-Schnellaktion ganz oben
- Live-TV hält den aktuell gewählten Sender über die stabile Enigma2-`serviceReference` statt über einen an die periodisch erneuerte Channel-Liste gebundenen Index. OpenWebif-/EPG-Refreshes dürfen den Player damit nicht mehr auf den ursprünglich gestarteten Sender zurücksetzen
- normales OK öffnet die Senderübersicht manuell und hält sie offen; Zurück schließt nur die Übersicht, eine Senderwahl schließt sie beim Umschalten. Langes OK bleibt der Direktzugang zum EPG
- transient eingeblendete Player-Informationen dürfen weiterhin nach drei Sekunden verschwinden; eine bewusst geöffnete Senderübersicht ist von diesem Timeout ausgenommen
- der Live-TV-Overlay-Look wurde weiter an die restliche Google-TV-inspirierte UI angeglichen: schwebende abgerundete Info-/Senderflächen, kleinere Fokus-Skalierung und kompaktere Senderkarten
- EPG als TV-Guide direkt im laufenden Live-TV-Player statt eigener Hauptnavigation; lange Programmbeschreibungen sind per D-Pad scrollbar
- bei sichtbarem Overlay gehören Hoch/Runter der UI-Navigation, bei ausgeblendetem Overlay dienen Hoch/Runter zum Zappen; CH+/CH− bleiben Senderwechsel
- Verlassen des Live-TV-Players erfordert eine Bestätigung. Im Dialog steht `TV verlassen` vor `Abbrechen` und ist standardmäßig fokussiert
- EPG-TMDB-Anreicherung bleibt nicht an fehlenden/unklaren XMLTV-Kategorien hängen: nicht typisierte Programme laufen über TMDB-Multi-Search und müssen weiterhin die bestehende strenge Confidence-Schwelle erfüllen
- der EPG verfolgt das ausgewählte Programm über Senderreferenz und Startzeit statt über die alte Objektinstanz; dadurch kann eine asynchron nachgeladene TMDB-Kopie den `Details`-Button zuverlässig einblenden
- alte negative TMDB-Zuordnungen aus einer früheren Resolver-Policy werden erneut geprüft; neue negative Treffer bleiben nur kurz gecacht. Damit können zuvor fälschlich ungelöste Titel nach Resolver-Verbesserungen wieder aufgelöst werden
- Detailseiten verwenden nun ebenfalls die Google-TV-inspirierte Bildsprache: Artwork bleibt großflächig, ein linker horizontaler Verlauf sichert die Textlesbarkeit, der untere Verlauf bindet die Seite an den Hintergrund an und reine `TMDB`-/Quell-App-Zeilen werden vermieden; bei EPG-Details bleibt der Sendername als sinnvoller Kontext erhalten
- direkte TMDB-/YouTube-Trailer starten in einer eigenen hardwarebeschleunigten Trailer-Activity; deutsche TMDB-Videos werden bevorzugt, YouTube-UI/Untertitelpräferenz ist Deutsch
- globale Suche zeigt lokale Quellen und TMDB als getrennte TV-Reihen statt als lange gemischte Liste
- Suche ist als Browse-Hub gestaltet: kompaktes rundes Suchfeld, Mikrofon-Aktion, konsistente 16:9-Karten mit Text unter dem Artwork und ruhige horizontale Content-Reihen
- bei leerer Suche zeigt I Launcher TMDB-Browse-Reihen: `Serien im Trend`, `Filme im Trend`, `Top Science-Fiction-Serien` und `Top Science-Fiction-Filme`. Diese Online-Reihen werden nur im Suchbereich geladen und eine Stunde lokal gecacht
- lokale Suchreihen sind `Weiterschauen`, `Aus deinen Apps`, `Im TV`, `Apps` und `Filme & Serien`; lokale Quellen bleiben damit visuell von reinen TMDB-Treffern unterscheidbar
- lokale Suche berücksichtigt unveränderte Android-Watch-Next-Quelltitel sowie Preview-Channel-/Quellnamen zusätzlich zu angereicherten Medienfeldern; TMDB-Suchergebnisse werden bei identischer TMDB-Identität wieder mit lokalen Quellen verknüpft
- Android-Sprachsuche über einen kompakten Mikrofon-Button übernimmt erkannte Sprache in dieselbe Suchpipeline
- Detailseiten fokussieren beim Öffnen direkt die erste sinnvolle Aktion; Watch-Next-Details werden nach langem OK erst nach konsumiertem OK-Release geöffnet, damit der frisch fokussierte Wiedergabe-Button nicht unbeabsichtigt ausgelöst wird
- Suchziele erscheinen als Suchsymbol plus App-Name; das Symbol übernimmt wie der Text die aktuelle TV-Material-Fokusfarbe
- CloudStream-Suchhandoff erkennt Stable-, Prerelease-, Debug- und kombinierte Development-Paketvarianten dynamisch über den `cloudstreamsearch`-Intent
- Kodi-Core-`ACTION_SEARCH` wird nicht mehr verwendet: I Launcher fragt stattdessen Kodis exportierten Suggestions-Provider ab und öffnet nur einen starken Kodi-Bibliothekstreffer über die von Kodi selbst zurückgegebene Referenz; ohne sicheren Treffer wird Kodi normal geöffnet
- der Kodi-Handoff durchsucht nur die Kodi-Core-Bibliothek, keine beliebigen Kodi-Add-ons; Add-on-spezifische Suche bleibt eine eigene spätere Integration und wird nicht geraten
- CloudStreams aktuelle Such-Activity erzwingt beim Öffnen selbst die Soft-Tastatur; die externe `cloudstreamsearch`-Schnittstelle bietet keinen dokumentierten Parameter zum Unterdrücken, daher implementiert I Launcher keinen timing-basierten Back-Workaround
- zusätzlicher Touch-Kompatibilitätslayer für Handy-/Tablet-Smoke-Tests; TV-/D-Pad-Bedienung bleibt die Produktquelle der Wahrheit
- Medien-Detailansicht ist vertikal per Touch scrollbar; lange Beschreibungen und umgebrochene Aktionsbuttons sind auf dem Handy praktisch bestätigt

Die interne Trailerwiedergabe mit Bild und Ton, die EPG-Integration im Live-TV-Player, Suchreihen sowie mehrere Player-Aktionen wurden auf realer TV-Hardware bestätigt. Der jüngste Google-TV-inspirierte Home-/Search-Feinschliff wurde visuell grundsätzlich bestätigt; die neue Live-TV-Refresh-/Senderlistenlogik und der jüngste Detailseiten-Polish benötigen noch den nächsten TCL-Gerätetest.

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
