# Architektur

## Zielbild

I Launcher ist ein TV-first Content Launcher. Inhalte stehen vor Apps. Die Oberfläche bleibt werbefrei, Local First und D-Pad-zentriert. Google TV dient als Referenz für Informationshierarchie, Hero-Komposition, Rails, Fokusruhe und Flächennutzung, nicht als Vorlage für Werbung oder automatisch rotierende Recommendation-Carousels.

## Quellen und Datenfluss

Watch Next und Preview Channels werden über Androids TvProvider gelesen. Die Quellreihenfolge wird nicht ohne Grund verändert. Watch Next wird optional mit TMDB angereichert. Preview Channels bleiben provider-neutral; sichtbare Kanäle können lokal ein-/ausgeblendet werden.

Live TV kommt direkt von Gigablue/OpenWebif. XMLTV wird zusätzlich eingelesen, auf OpenWebif-Sender gemappt und lokal in Room gecacht. EPG-Programme können mit TMDB angereichert werden. Stream-URLs werden nicht dauerhaft gecacht.

TMDB ist die Metadaten- und Discovery-Quelle für Filme, Serien und Episoden. Die normale Suche bleibt Local First: Apps, Watch Next, Preview Channels und EPG werden zuerst lokal gesucht; TMDB ergänzt die Ergebnisse. Bei leerer Suche darf TMDB gecachte Browse-/Trend-/Genre-Reihen liefern. Auf Home wird daraus bewusst kein automatisch rotierendes Netzwerk-Karussell.

## Home und Navigation

Home besteht aus einem ruhigen Hero und darunter frei sortierbaren Content-Reihen. Die vertikale Reihenfolge sowie die App-Reihenfolge werden lokal gespeichert. `Home` lange OK öffnet die Home-spezifische Konfiguration; App lange OK startet den Verschiebemodus.

Der Start-Hero ist Local First: erster Watch-Next-Inhalt, danach erster sichtbarer Preview-Program-Inhalt, danach neutraler Fallback. Live TV übernimmt ihn erst durch aktiven Fokus. Das Fokussieren der Apps-Reihe ändert den Medien-Hero nicht, damit die Bühne visuell stabil bleibt.

Der Hero ist eine cinematische Vollbreitenfläche. Echte Backdrops füllen die Bühne mit `Crop`. Poster, 4:3- und sonstige ungeeignete Quellbilder bekommen einen schwachen Crop-Hintergrund plus möglichst unbeschnittenes `Fit`-Motiv rechts. Horizontaler und vertikaler Verlauf sichern die Lesbarkeit links und verbinden den Hero weich mit den Rails. Der Textblock ist unten links verankert und zeigt Logo oder Titel, eine kompakte Metadatenzeile, Beschreibung und einen visuellen Primär-CTA. Der gesamte Hero bleibt das eigentliche fokussierbare Element; der CTA ist keine zweite Fokusstation. Hero-Wechsel erfolgen per kurzer Crossfade-Animation. Lange Beschreibungen starten erst nach einer deutlichen Lesepause und scrollen langsam.

Media-Rails verwenden ein einheitliches ungefähr 16:9-Raster mit kleinem Fokus-Zoom. Titel bleiben sichtbar, Sekundärinformationen werden außerhalb des Fokus bewusst zurückgenommen. Watch Next, Preview Channels und Live TV teilen dadurch dieselbe visuelle Sprache. Die Apps-Reihe ist als kompakter Icon-Dock gestaltet: große runde App-Icons, Labels nur bei Fokus oder Verschiebemodus.

Die primäre Navigation bleibt klein: `Home · Suche · Apps · Einstellungen`. Im Ruhezustand sind die Buttonflächen transparent; der aktuell aktive Bereich bleibt durch einen dezenten Rahmen erkennbar. Der tatsächliche D-Pad-Fokus wird als helle kompakte Pill mit geringer Größenänderung dargestellt. Der Fokuspfad selbst bleibt unverändert. Auf Home wird die Navigation sichtbar, wenn der Fokus ganz oben im Hero-/Navigationsbereich liegt, und ausgeblendet, sobald der Fokus in eine Inhaltsreihe wechselt. Live-TV-Konfiguration liegt unter Einstellungen; der EPG ist Player-Funktion und kein eigener Hauptpunkt.

Die separate `Alle Apps`-Ansicht verwendet dasselbe App-Kartenmodell wie Home, aber mit dauerhaft sichtbaren Labels. Das Grid ist adaptiv statt auf eine feste Spaltenzahl festgelegt, damit TV-, Tablet- und Smoke-Test-Breiten sinnvoll genutzt werden, ohne die reduzierte Home-App-Reihe aufzublähen.

## Suche und Discover

Die Suchoberfläche hat zwei Zustände. Bei leerer Eingabe ist sie ein Google-TV-inspirierter Discover-Hub: oben eine breite ruhige Suchfläche mit separater Sprachsuche, direkt darunter auswählbare Beispielanfragen und anschließend TMDB-Browse-Reihen, beispielsweise Trends und Genre-/Qualitätslisten. Die Beispielanfragen verwenden denselben normalen Suchpfad und sind keine separate Empfehlungslogik.

Bei expliziter Eingabe bleiben lokale Quellen getrennt und vorrangig (`Weiterschauen`, `Aus deinen Apps`, `Im TV`, `Apps`), während TMDB ergänzend als `Filme & Serien` erscheint. Kompakte Filter-Pills (`Alle`, `Filme & Serien`, `TV`, `Apps`) filtern ausschließlich die bereits vorhandenen Ergebnisgruppen in der Oberfläche; sie ändern weder Backend-Suche noch Quellenreihenfolge.

Ergebnis- und Browse-Rails verwenden dieselbe kompakte ungefähr 16:9-Formsprache wie Home. Nur die Artwork-Fläche ist fokussierbar und erhält Rahmen/Zoom; Titel und Sekundärinformationen bleiben ruhig darunter. Sekundärtexte werden bei nicht fokussierten Karten visuell zurückgenommen. Die Suchgeometrie wird im deterministischen 1920×1080-TV-Visual-Smoke zusätzlich zu Home mit Discover-, Query- und Fokus-Screenshots geprüft.

## Detailnavigation und App-Handoff

Kurzes OK auf Watch Next startet den vorhandenen Source-/Playback-Intent. `INFO` oder langes OK öffnet Details. Das Loslassen der langen OK-Taste wird konsumiert, bevor die Detailseite aufgebaut wird, damit kein frisch fokussierter Button unbeabsichtigt ausgelöst wird.

Detailseiten folgen derselben Google-TV-inspirierten Bildhierarchie wie Home: das Artwork bleibt großflächig sichtbar, ein starker horizontaler Verlauf sichert links die Lesbarkeit und ein unterer Verlauf verbindet die Fläche mit dem Hintergrund. Reine technische Quellenbezeichnungen wie `TMDB` oder App-Namen werden nicht als zusätzliche Informationszeile wiederholt; bei EPG-Inhalten kann der Sendername als echter Kontext erhalten bleiben. Die vorhandene Aktionsreihenfolge und der direkte Fokus auf die erste sinnvolle Aktion bleiben unverändert.

TMDB-/EPG-Details bieten verfügbare Zielaktionen in sinnvoller Reihenfolge. CloudStream wird über den offiziellen `cloudstreamsearch://`-Intent aufgelöst. CloudStreams eigene Suchoberfläche erzwingt derzeit selbst die Soft-Tastatur; I Launcher sendet keinen timing-basierten Back-Key-Workaround.

Kodis Android-`ACTION_SEARCH`-Activity wird nicht verwendet, weil sie intern einen vom eigenen Media-Provider nicht registrierten `content://…media/search/<query>`-Pfad abfragt. Der Adapter nutzt stattdessen Kodis exportierten Suggestions-Provider und akzeptiert nur einen starken normalisierten Bibliothekstitel-Treffer. Dessen von Kodi selbst zurückgegebene `ACTION_GET_CONTENT`-/`videodb://`-Referenz wird an Kodi übergeben. Ohne sicheren Treffer wird Kodi normal geöffnet. Add-on-spezifische Kodi-Suchen bleiben getrennte Adapter.

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

Vor einer Test-APK laufen `testDebugUnitTest` und `assembleDebug`. Signierte Development-Builds werden über den `downloads`-Kanal veröffentlicht. Kompilierung ersetzt keinen Hardwaretest; D-Pad, Fokus, Hero-Proportionen, Bildausschnitt, Kodi-Handoff und Player-Navigation bleiben bis zur TV-Bestätigung offen.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService` mit `BIND_ACCESSIBILITY_SERVICE` und `canRequestFilterKeyEvents=true`. Restricted Settings bei lokal installierten APKs bleiben ein separates Distributionsthema.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
