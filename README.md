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

- TV-first Hero als cinematische Vollbreitenfläche: TMDB-verknüpfte Filme/Serien verwenden ausschließlich breite Backdrops als bildfüllenden Hintergrund; Episoden bevorzugen ihr Still und danach das Serien-Backdrop. Poster/Hochformatbilder sind kein primäres Home-Hero-Fallback
- der Hero liegt hinter der ersten Content-Reihe statt als abgeschlossener Block darüber. Die erste Rail überlappt den unteren Hero, sodass Artwork und Fade ungefähr bis in die obere Hälfte der Karten hinein sichtbar bleiben
- bildfüllende Hero-Backdrops sind oben ausgerichtet (`TopCenter`), damit der obere Motivbereich beim Crop erhalten bleibt statt vertikal mittig abgeschnitten zu werden
- nicht mit TMDB verknüpfte EPG-/Quellbilder bleiben möglichst unbeschnitten oben rechts und laufen anhand ihrer tatsächlich geladenen Bildgrenzen nach links sowie unten weich aus
- der frühere globale Außenabstand um die gesamte App ist entfernt. Hero/Hintergrund dürfen bis an die Bildschirmkante zeichnen; notwendige Safe-Area-Abstände liegen nur noch lokal an Text, Navigation und Rails
- fokussierte Medienkarten besitzen einen dynamischen, **inhaltsspezifischen Glow**: dieselbe Artwork-Quelle wird hinter der Karte weich geblurrt, sodass der Halo automatisch die Farben des Inhalts übernimmt
- die helle Kartenkontur besitzt einen langsamen `Breath`-Effekt über Breite und Deckkraft. Nur die tatsächlich fokussierte Karte hält diese Animation aktiv
- Home-Medienreihen verwenden weiterhin ein einheitliches 16:9-Raster und kleine Fokus-Skalierung; Titel bleiben außerhalb der fokussierten Bildfläche
- Hero startet nicht mit dem ersten TV-Sender: zunächst wird Local First ein Watch-Next-Inhalt, danach ein Preview-Channel-Inhalt und sonst ein neutraler Launcher-Hero verwendet; ein automatisch rotierendes Netzwerk-/Werbekarussell gibt es bewusst nicht
- Hero vermeidet doppelte Medienidentität: bei vorhandenem Titellogo wird derselbe Titel nicht noch einmal als große Überschrift gezeigt; Quell-App-Namen wie CloudStream werden im Medien-Hero nicht wiederholt
- Watch-Next-Episoden können unter `Home anpassen` getrennt für **Karten** und **Hero** auf `Episodenbild` oder `Serienbild` gestellt werden. Die Voreinstellung bleibt `Episodenbild`
- Preview Channels haben unter `Home anpassen` je Kanal einen eigenen TMDB-Schalter. TMDB-Anreicherung ist standardmäßig **aus**; bei Aktivierung werden die Inhalte dieses Kanals über den zentralen Resolver aufgelöst und für Home sowie lokale Suche angereichert
- Live-TV-Fokus stößt für das aktuelle Programm bei Bedarf gezielt die TMDB-Auflösung an. Die ausgewählte Hero-Kopie bleibt über `serviceReference + startUtcMillis` gebunden und übernimmt nachgeladene TMDB-Bilder/Metadaten ohne Fokuswechsel
- der TMDB-Resolver behandelt ein Quelljahr nicht mehr als absolut: nach einer erfolglosen typisierten Suche mit Jahr folgt genau ein unfiltrierter Suchlauf, während das ursprüngliche Jahr weiterhin in die Confidence einfließt. Damit kann z. B. `S1:E1 ZeroZeroZero (2019)` trotz um ein Jahr abweichendem TMDB-Serienjahr sicher aufgelöst werden
- Home-Reihen sind persistent sortierbar. Langes OK auf `Home` öffnet die Home-Anpassung mit Reihenfolge, Watch-Next-Quellen, Bildwahl, App-Kanälen und Reset-Funktionen
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
- Detailseiten verwenden ebenfalls die Google-TV-inspirierte Bildsprache: Artwork bleibt großflächig, ein linker horizontaler Verlauf sichert die Textlesbarkeit, der untere Verlauf bindet die Seite an den Hintergrund an und reine `TMDB`-/Quell-App-Zeilen werden vermieden; bei EPG-Details bleibt der Sendername als sinnvoller Kontext erhalten
- direkte TMDB-/YouTube-Trailer starten in einer eigenen hardwarebeschleunigten Trailer-Activity; deutsche TMDB-Videos werden bevorzugt, YouTube-UI/Untertitelpräferenz ist Deutsch
- globale Suche zeigt lokale Quellen und TMDB als getrennte TV-Reihen statt als lange gemischte Liste
- Suche ist als Browse-Hub gestaltet: kompaktes rundes Suchfeld, Mikrofon-Aktion, konsistente 16:9-Karten mit Text unter dem Artwork und ruhige horizontale Content-Reihen
- bei leerer Suche zeigt I Launcher TMDB-Browse-Reihen: `Serien im Trend`, `Filme im Trend`, `Top Science-Fiction-Serien` und `Top Science-Fiction-Filme`. Diese Online-Reihen werden nur im Suchbereich geladen und eine Stunde lokal gecacht
- lokale Suchreihen sind `Weiterschauen`, `Aus deinen Apps`, `Im TV`, `Apps` und `Filme & Serien`; lokale Quellen bleiben damit visuell von reinen TMDB-Treffern unterscheidbar
- Android-Sprachsuche über einen kompakten Mikrofon-Button übernimmt erkannte Sprache in dieselbe Suchpipeline
- CloudStream-Suchhandoff erkennt Stable-, Prerelease-, Debug- und kombinierte Development-Paketvarianten dynamisch über den `cloudstreamsearch`-Intent
- `In Kodi suchen` öffnet jetzt direkt die Suchroute des installierten **TMDb Helper**-Add-ons mit dem übergebenen Titel. Da Stock-Kodi keinen Android-Intent zum direkten Öffnen einer beliebigen Plugin-Directory anbietet, wird dafür nach dem Kodi-Start Kodis lokale JSON-RPC-Methode `GUI.ActivateWindow` verwendet; Kodis lokale Programm-Fernsteuerung muss aktiviert sein
- CloudStreams aktuelle Such-Activity erzwingt beim Öffnen selbst die Soft-Tastatur; die externe `cloudstreamsearch`-Schnittstelle bietet keinen dokumentierten Parameter zum Unterdrücken, daher implementiert I Launcher keinen timing-basierten Back-Workaround
- zusätzlicher Touch-Kompatibilitätslayer für Handy-/Tablet-Smoke-Tests; TV-/D-Pad-Bedienung bleibt die Produktquelle der Wahrheit

Die aktuellen Anwendungsänderungen sind als signierter Development-Build **`0.1.0-dev.261` (`26000261`)** veröffentlicht. Android CI **#472** und TV Visual Smoke **#81** sind grün; der 1920×1080-Smoke wurde manuell auf Hero/Rail-Überlagerung, edge-to-edge Darstellung, EPG-Fallback und den statischen Focus-Glow/Border-Zustand geprüft. Der zeitliche Breath-Effekt, reales Live-TV-TMDB-Nachladen, die neuen Home-Optionen und der Kodi-TMDb-Helper-Handoff benötigen noch den TCL-Gerätetest.

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
