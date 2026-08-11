# Architektur

## Zielbild

I Launcher ist ein TV-first Content Launcher. Inhalte stehen vor Apps. Die Oberfläche bleibt werbefrei, Local First und D-Pad-zentriert. Google TV dient als Referenz für Informationshierarchie, Hero-Komposition, Rails, Fokusruhe und Flächennutzung, nicht als Vorlage für Werbung oder automatisch rotierende Recommendation-Carousels.

## Quellen und Datenfluss

Watch Next und Preview Channels werden über Androids TvProvider gelesen. Die Quellreihenfolge wird nicht ohne Grund verändert. Watch Next wird optional mit TMDB angereichert. Preview Channels bleiben provider-neutral; sichtbare Kanäle können lokal ein-/ausgeblendet werden. TMDB-Anreicherung von Preview Channels ist bewusst **pro Kanal opt-in und standardmäßig aus**. Wird sie aktiviert, wird der normalisierte Inhalt dieses Kanals über denselben zentralen TMDB-Resolver aufgelöst und die angereicherte Medienkopie für Home und lokale Suche verwendet.

Live TV kommt direkt von Gigablue/OpenWebif. XMLTV wird zusätzlich eingelesen, auf OpenWebif-Sender gemappt und lokal in Room gecacht. EPG-Programme können mit TMDB angereichert werden. Beim Fokus eines aktuellen Live-TV-Programms darf die noch fehlende TMDB-Auflösung gezielt angestoßen werden; sobald die über `serviceReference + startUtcMillis` identifizierte angereicherte Programmkopie vorliegt, aktualisiert sich der ausgewählte Hero ohne Fokuswechsel. Stream-URLs werden nicht dauerhaft gecacht.

TMDB ist die Metadaten- und Discovery-Quelle für Filme, Serien und Episoden. Die normale Suche bleibt Local First: Apps, Watch Next, Preview Channels und EPG werden zuerst lokal gesucht; TMDB ergänzt die Ergebnisse. Bei leerer Suche darf TMDB gecachte Browse-/Trend-/Genre-Reihen liefern. Auf Home wird daraus bewusst kein automatisch rotierendes Netzwerk-Karussell.

Der TMDB-Resolver behandelt ein vom Provider geliefertes Erscheinungsjahr als starken Hinweis, aber nicht als unfehlbare Serverfilter-Bedingung. Bei typisierten Film-/Serien-/Episodensuchen wird zuerst mit dem Quelljahr gesucht. Ergibt das keinen sicheren Match, folgt genau ein Suchlauf ohne serverseitigen Jahresfilter; das Quelljahr bleibt anschließend Bestandteil der Confidence-Berechnung. Dadurch kann z. B. ein exakt passender Serientitel mit um ein Jahr abweichendem Providerjahr weiterhin sicher aufgelöst werden, ohne die bestehende Match-Schwelle zu lockern.

## Home und Navigation

Home besteht aus einem ruhigen Hero und frei sortierbaren Content-Reihen. Die vertikale Reihenfolge sowie die App-Reihenfolge werden lokal gespeichert. `Home` lange OK öffnet die Home-spezifische Konfiguration; App lange OK startet den Verschiebemodus.

Der Start-Hero ist Local First: erster Watch-Next-Inhalt, danach erster sichtbarer Preview-Program-Inhalt, danach neutraler Fallback. Live TV übernimmt ihn erst durch aktiven Fokus. Das Fokussieren der Apps-Reihe ändert den Medien-Hero nicht, damit die Bühne visuell stabil bleibt.

Der Hero ist eine cinematische Vollbreitenfläche und liegt **hinter der ersten Content-Reihe**. Die erste Rail beginnt bereits im unteren Hero-Bereich; Artwork und unterer Verlauf dürfen dadurch ungefähr bis in die obere Hälfte der ersten Karten hinein sichtbar bleiben. Der Hero ist nicht als eigener Block oberhalb der Rails zu verstehen. TMDB-verknüpfte Filme und Serien verwenden ausschließlich ein breites Backdrop als bildfüllenden `Crop`-Hintergrund; Episoden verwenden zuerst ein Episoden-Still und danach das Serien-Backdrop. Vollflächige Backdrops werden `TopCenter` ausgerichtet, damit der obere Bildbereich beim notwendigen Crop erhalten bleibt und das Motiv nicht vertikal mittig abgeschnitten wird.

Ein Poster/Hochformatbild wird im Home-Hero nicht als primäres TMDB-Fallback eingesetzt. Fehlt der primäre TMDB-`backdrop_path`, darf der Resolver aus den mitgelieferten Backdrops einen geeigneten sprachneutralen Kandidaten auswählen. Nicht mit TMDB verknüpfte EPG-/Quellbilder werden möglichst unbeschnitten per `Fit` oben rechts platziert und nicht zusätzlich als vergrößerter Vollflächen-Crop dupliziert. Nach dem Laden wird das tatsächliche Seitenverhältnis des Quellbilds berücksichtigt; der linke und untere Verlauf werden aus den real gerenderten `Fit`-Bildgrenzen berechnet, damit 2:3-, 4:3- und 16:9-Quellbilder unabhängig vom Format weich in den Hintergrund auslaufen.

Für Watch-Next-Episoden sind Karten- und Hero-Artwork getrennt konfigurierbar. `Episodenbild` bevorzugt das Episoden-Still, `Serienbild` bevorzugt das Serien-Backdrop. Die Einstellung ändert nur die Darstellung und nicht Source-Reihenfolge, Deep Link oder TMDB-Identität.

Der Textblock liegt links über dem Hero und bleibt oberhalb der überlappenden ersten Rail. Er zeigt Logo oder Titel, eine kompakte Metadatenzeile, Beschreibung und einen visuellen Primär-CTA. Der gesamte Hero bleibt das eigentliche fokussierbare Element; der CTA ist keine zweite Fokusstation. Hero-Wechsel erfolgen per kurzer Crossfade-Animation. Lange Beschreibungen starten erst nach einer deutlichen Lesepause und scrollen langsam.

Media-Rails verwenden ein einheitliches ungefähr 16:9-Raster mit kleinem Fokus-Zoom. Fokussierte Medienkarten erhalten einen **inhaltsspezifischen Glow**, indem dieselbe Artwork-Quelle hinter der Karte vergrößert und auf Android 12+ weich geblurrt wird. Dadurch übernimmt der Halo automatisch die Farben des aktuellen Inhalts, ohne Palette-Bibliothek oder zusätzliche Netzwerkanfrage. Die helle Kartenkontur besitzt zusätzlich einen langsamen `Breath`-Effekt über Breite und Alpha. Nur die aktuell fokussierte Karte hält die unendliche Animation aktiv; unfokussierte Karten erzeugen keine dauernden Animationskosten. Auf älteren Android-Versionen ohne Compose-Hardwareblur bleibt nur ein bewusst schwacher Farbaura-Fallback.

Titel bleiben sichtbar, Sekundärinformationen werden außerhalb des Fokus bewusst zurückgenommen. Watch Next, Preview Channels und Live TV teilen dadurch dieselbe visuelle Sprache. Die Apps-Reihe ist als kompakter Icon-Dock gestaltet: große runde App-Icons, Labels nur bei Fokus oder Verschiebemodus.

Der frühere globale Außen-Padding-Rahmen ist entfernt. Home darf Hero und Hintergrund bis an die tatsächlichen Bildschirmkanten zeichnen. Notwendige Lesbarkeits-/Focus-Abstände werden lokal an Navigation, Textachsen und Rails vergeben, nicht als appweiter Rahmen um die gesamte Oberfläche.

Die primäre Navigation bleibt klein: `Home · Suche · Apps · Einstellungen`. Im Ruhezustand sind die Buttonflächen transparent; der aktuell aktive Bereich bleibt durch einen dezenten Rahmen erkennbar. Der tatsächliche D-Pad-Fokus wird als helle kompakte Pill mit geringer Größenänderung dargestellt. Der Fokuspfad selbst bleibt unverändert. Auf Home wird die Navigation sichtbar, wenn der Fokus ganz oben im Hero-/Navigationsbereich liegt, und ausgeblendet, sobald der Fokus in eine Inhaltsreihe wechselt. Live-TV-Konfiguration liegt unter Einstellungen; der EPG ist Player-Funktion und kein eigener Hauptpunkt.

Die separate `Alle Apps`-Ansicht verwendet dasselbe App-Kartenmodell wie Home, aber mit dauerhaft sichtbaren Labels. Das Grid ist adaptiv statt auf eine feste Spaltenzahl festgelegt, damit TV-, Tablet- und Smoke-Test-Breiten sinnvoll genutzt werden, ohne die reduzierte Home-App-Reihe aufzublähen.

## Suche und Discover

Die Suchoberfläche hat zwei Zustände. Bei leerer Eingabe ist sie ein Discover-Hub mit TMDB-Browse-Reihen, beispielsweise Trends und Genre-/Qualitätslisten. Bei expliziter Eingabe bleiben lokale Quellen getrennt und vorrangig (`Weiterschauen`, `Aus deinen Apps`, `Im TV`, `Apps`), während TMDB ergänzend als `Filme & Serien` erscheint.

Das Suchfeld ist kompakt und TV-gerecht mit separater Sprachsuche. Ergebnis- und Browse-Rails verwenden dieselbe ruhige 16:9-Formsprache wie Home. Sekundärtexte werden bei nicht fokussierten Karten visuell zurückgenommen, damit Artwork und Fokus klarer wirken.

## Detailnavigation und App-Handoff

Kurzes OK auf Watch Next startet den vorhandenen Source-/Playback-Intent. `INFO` oder langes OK öffnet Details. Das Loslassen der langen OK-Taste wird konsumiert, bevor die Detailseite aufgebaut wird, damit kein frisch fokussierter Button unbeabsichtigt ausgelöst wird.

Detailseiten folgen derselben Google-TV-inspirierten Bildhierarchie wie Home: das Artwork bleibt großflächig sichtbar, ein starker horizontaler Verlauf sichert links die Lesbarkeit und ein unterer Verlauf verbindet die Fläche mit dem Hintergrund. Reine technische Quellenbezeichnungen wie `TMDB` oder App-Namen werden nicht als zusätzliche Informationszeile wiederholt; bei EPG-Inhalten kann der Sendername als echter Kontext erhalten bleiben. Die vorhandene Aktionsreihenfolge und der direkte Fokus auf die erste sinnvolle Aktion bleiben unverändert.

TMDB-/EPG-Details bieten verfügbare Zielaktionen in sinnvoller Reihenfolge. CloudStream wird über den offiziellen `cloudstreamsearch://`-Intent aufgelöst. CloudStreams eigene Suchoberfläche erzwingt derzeit selbst die Soft-Tastatur; I Launcher sendet keinen timing-basierten Back-Key-Workaround.

`In Kodi suchen` zielt auf das installierte **TMDb Helper**-Add-on und übergibt den normalisierten Titel direkt an dessen aktuelle Suchroute `info=search&tmdb_type=both&query=…`. Stock-Kodi bietet auf Android keinen passenden öffentlichen Intent zum direkten Öffnen einer beliebigen Plugin-Directory. Deshalb startet I Launcher Kodi normal und verwendet anschließend Kodis lokale JSON-RPC-Schnittstelle `GUI.ActivateWindow` für den konkreten `plugin://plugin.video.themoviedb.helper/…`-Pfad. Dafür muss in Kodi die Fernsteuerung durch Programme auf demselben System aktiviert sein; ist der lokale JSON-RPC-Dienst nicht erreichbar, wird genau diese notwendige Kodi-Einstellung angezeigt statt ein timing-basierter Key-Workaround ausgeführt.

## Trailer

Trailer werden bevorzugt über TMDB-Video-Metadaten aufgelöst. Wenn eine konkrete YouTube-ID vorhanden ist, läuft der Trailer in einer internen Activity mit hardwarebeschleunigtem WebView und `WebChromeClient`-Fullscreen-Unterstützung. Deutsch wird bei TMDB-Videoauswahl, Oberfläche und Untertiteln bevorzugt, sofern vorhanden. Es findet keine Stream-Extraktion statt. Ohne konkrete ID bleibt die externe YouTube-Suche der Fallback.

## Live TV und EPG

Der Live-TV-Player nutzt Media3. Die aktuell gewählte Senderidentität wird über die stabile Enigma2-`serviceReference` gehalten und nicht über einen `remember`-Index, der an eine periodisch neu gelieferte Channel-Liste gekoppelt ist. OpenWebif-/EPG-Metadatenrefreshes dürfen damit die aktuelle Wiedergabe nicht mehr auf den ursprünglich gestarteten Sender zurücksetzen. Nur wenn die gewählte `serviceReference` tatsächlich aus der Bouquetliste verschwindet, fällt der Player sicher auf den ersten verfügbaren Sender zurück.

Die Player-UI unterscheidet zwischen transient eingeblendeten Informationen und einer bewusst geöffneten Senderübersicht. Normales OK öffnet die Übersicht und hält sie offen; sie ist vom Drei-Sekunden-Timeout ausgenommen. Zurück schließt zuerst nur diese Übersicht. Eine konkrete Senderwahl oder ein Senderwechsel beendet den angehefteten Listenmodus. Langes OK bleibt der direkte EPG-Zugang. Bei ausgeblendetem Overlay gilt Hoch = höhere Kanalnummer, Runter = niedrigere; CH+/CH− bleiben explizite Senderwechsel.

Der Player übernimmt dieselbe ruhige visuelle Sprache wie Home und Search: kompakte abgerundete, leicht transparente Info-/Senderflächen, kleine Fokusvergrößerungen und zurückgenommene Sekundärtexte statt vollflächiger schwerer Balken.

Beim Verlassen erscheint ein Bestätigungsdialog. EPG-Programme werden über `serviceReference + startUtcMillis` identifiziert, damit asynchrone TMDB-Anreicherung die sichtbare Programmkopie aktualisieren kann. Eindeutig angereicherte Programme können Details öffnen und von dort wieder CloudStream/Kodi/Trailer erreichen.

## Cache und Local First

Room enthält den TMDB-Cache sowie EPG-Sender-Mappings und EPG-Programme. XMLTV und Metadaten werden lokal gecacht. Netzwerk-Browse-Ergebnisse werden nicht bei jeder Fokusbewegung neu geladen. Streaming-Adressen werden nicht dauerhaft gespeichert.

## Touch-Smoke-Tests

TV/D-Pad bleibt die Produktquelle der Wahrheit. Derselbe Development-Build kann zusätzlich auf Smartphone/Tablet installiert werden. `TouchButton`, `TouchCard` und Scroll-Fallbacks ergänzen Pointer-Eingabe, ohne die TV-Fokuslogik zu ersetzen.

## Development-Publishing

Vor einer Test-APK laufen `testDebugUnitTest` und `assembleDebug`. Signierte Development-Builds werden über den `downloads`-Kanal veröffentlicht. Kompilierung ersetzt keinen Hardwaretest; D-Pad, Fokus, Hero-Proportionen, Bildausschnitt, Glow-/Breath-Wirkung, Kodi-TMDb-Helper-Handoff und Player-Navigation bleiben bis zur TV-Bestätigung offen.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService` mit `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true`. Restricted Settings bei lokal installierten APKs bleiben ein separates Distributionsthema.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
