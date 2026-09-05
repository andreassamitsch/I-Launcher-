# ServusTV – Standalone-App + Android-TV-Provider

## Ziel

Die native Kotlin-App ersetzt für ServusTV-Inhalte den langsamen Kodi-Startpfad. Sie funktioniert eigenständig auf Android-Handys/Tablets und veröffentlicht auf Android-TV-Geräten Preview Channels, die I Launcher provider-neutral über `TvProvider` einliest.

## Bestehender Kanal `ServusTV Aktuelles`

Der bereits auf realer TCL-Hardware bestätigte Kanal bleibt unverändert als stabile Sammelquelle bestehen. Er enthält chronologisch:

- Servus Nachrichten 19:20
- Servus Nachrichten in 90 Sekunden
- Der Wegscheider

Die interne Kanal-ID `servus-news-19-20` bleibt absichtlich erhalten, damit bestehende Launcher-Präferenzen stabil bleiben.

## Sendungskatalog

Der vollständige Sendungskatalog wird nicht aus HTML gescrapt. Ausgangspunkt ist das aktuelle ServusTV-/Red-Bull-Produkt `sendungen`. Dessen Collections bilden die Kategorien der ServusTV-Sendungsseite ab und werden in Quellreihenfolge übernommen.

Für jede Sendung wird im Katalog zunächst nur die leichte Metadatenbasis gespeichert:

- stabile ServusTV-ID
- Titel und Beschreibung, soweit bereits auf der Katalogkarte vorhanden
- Kategorie
- Landscape-/Square-Artwork
- bereits lokal bekannte Detail-/Logo-/Folgendaten bleiben erhalten

Folgen werden bewusst **nicht mehr für jede Katalogsendung** geladen. Eine Sendung lädt ihre Detail-Collections und Folgen nur, wenn sie geöffnet wird, explizit zu `ServusTV Aktuelles` hinzugefügt wurde oder der Benutzer sie als eigenen Android-TV-Kanal aktiviert hat.

Auf Android TV sind Sendungskanäle damit opt-in. Die Sendungsansicht besitzt eine dezente TV-Kanal-Aktion; nur ausgewählte Sendungen erhalten einen Preview Channel mit stabiler interner ID `servus-show:<showId>`. Beim Entfernen wird der zugehörige Preview Channel wieder aus dem TvProvider gelöscht.

Bei Sendungen mit echten `episode`-/`film`-Einträgen werden diese für den Kanal bevorzugt. Nur wenn eine Sendung keine solchen Vollinhalte liefert, dienen andere abspielbare Videos als Fallback. Pro Sendung werden aktuell höchstens 18 neueste Einträge veröffentlicht.

Wenn Folgen für eine ausgewählte oder geöffnete Sendung tatsächlich benötigt werden, dienen deren Collections als Discovery-/Reihenfolgequelle. Für höchstens 20 plausible aktuelle Folgen werden die jeweiligen `products/v5.3/.../<contentId>`-Details nur dann nachgeladen, wenn Collection-Karte und lokaler Cache noch keinen belastbaren VOD-Zeitstempel liefern. Die Detailaufrufe sind global auf sechs parallele Requests begrenzt. `start_time`, `end_time` und Uhrzeiten aus Titeln bleiben ausdrücklich EPG-/Broadcastdaten und werden nicht als Veröffentlichungszeit verwendet.

## App-Oberfläche

Die Standalone-App zeigt Local First:

1. Aktuelles
2. Live TV
3. die von ServusTV gelieferten Kategorien
4. darin die Sendungen als horizontale Reihen

Eine Sendung öffnet eine eigene Detailansicht mit Artwork, ServusTV-Titel-Logo, Beschreibung und Folgen/Videos. TV-Fokus und Touch werden unterstützt.

## Live TV

Die Liste der Live-Kanäle wird dynamisch aus der von der aktuellen ServusTV-Implementierung verwendeten Channel-Collection geladen. Es wird keine feste Senderliste im Code gepflegt.

Für jeden Live-Kanal wird zusätzlich der ServusTV-Guide geladen. Die Standalone-App kann dadurch bei der Live-Karte das aktuell laufende Programm anzeigen.

Auf Android TV werden alle Live-Stationen in einem zusätzlichen Preview Channel `ServusTV Live` veröffentlicht. Die Programme der Rail sind die einzelnen direkt startbaren Live-Stationen.

Playback:

- Haupt-ServusTV-Livestream: tokenisierter `stv-linear`-HLS-Pfad
- digitale ServusTV-Kanäle: `destination/stv/<channelId>/personal_computer/http/de/<market>/playlist.m3u8`
- Stream-URLs werden weder persistiert noch geloggt
- vor Wiedergabe wird das Manifest geprüft; Session-Erneuerung/Fallback bleiben erhalten

## Logos und Artwork

ServusTV liefert Media-Resources pro Produkt. Für Sendungsbranding wird bevorzugt `rbtv_title_treatment` verwendet. Artwork bleibt getrennt davon Landscape/Square. CDN-Bilder werden als WebP angefordert, damit sie über die komplette Android-minSdk-Spanne nutzbar bleiben.

## Local First / Aktualisierung

`Aktuelles` sowie Live/Guide werden mit dem vorhandenen Hintergrundrefresh aktualisiert. Der vollständige Sendungskatalog ist deutlich größer und wird deshalb nicht alle 15 Minuten erneut geladen:

- sofort, wenn lokal noch kein Katalog vorhanden ist
- anschließend ungefähr alle sechs Stunden
- bei einem ausdrücklich erzwungenen Diagnose-/Vollrefresh

`Jetzt aktualisieren` verwendet im Normalfall denselben schnellen Pfad wie der Hintergrundrefresh. Nur wenn der Sendungskatalog fehlt oder ohnehin älter als sechs Stunden ist, wird dabei zusätzlich der Vollkatalog geladen.

Auch `Aktuelles` arbeitet inkrementell: Die vier Suchbegriffe laden nur die vom Server über `meta.next` angekündigten Seiten (maximal drei pro Suchbegriff). Bereits lokal vollständig bekannte Content-IDs werden nicht bei jedem 15-Minuten- oder manuellen Refresh erneut über den Produkt-Endpunkt geladen; nur neue IDs werden hydratisiert. Ein getesteter `limit`-Queryparameter wurde vom aktuellen ServusTV-Endpunkt nicht eingehalten und wird deshalb bewusst nicht verwendet. Ein direktes Produkt `nachrichten` existiert im getesteten AT-Markt ebenfalls nicht.

Für explizit zu `Aktuelles` hinzugewählte Sendungen und für aktivierte Android-TV-Sendungskanäle bleibt ein leichter gezielter Refresh bestehen. Nur diese Sendungen werden bei einem normalen Hintergrund-/manuellen Refresh auf neue Folgen geprüft. Nicht ausgewählte Sendungen verursachen dabei keinen Folgen-/Produktdetail-Traffic; sie werden erst beim Öffnen ihrer Sendungsansicht on demand geladen.

Der lokale Cache bleibt auch auf Smartphones ohne Android-TvProvider die primäre Datenquelle. Ein fehlender TvProvider darf Datenabruf und Standalone-App nicht scheitern lassen.

## Playback

Die bereits auf TCL bestätigte Media3-Konfiguration bleibt bestehen:

- höchste vom Gerät unterstützte HLS-Variante
- Tunneling, sofern vom Gerät unterstützt
- D-Pad links/rechts ±10 Sekunden
- OK = Play/Pause
- reduzierte untere Steuerleiste; Player-Einstellungen über ein kleines Zahnrad direkt neben der Zeitleiste
- Touch Play/Pause und Zeitleisten-Scrubbing
- auf Smartphone folgt der Player der Geräte-Rotation zwischen Hoch- und Querformat, ohne den Player wegen einer Konfigurationsänderung neu aufzubauen
- VOD beendet die PlaybackActivity bei `Player.STATE_ENDED`

## Noch ausstehender Gerätetest dieses Ausbaus

### TCL / I Launcher

1. App aktualisieren und einmal `Jetzt aktualisieren` auslösen. Bei vorhandenem, frischem Sendungskatalog muss dieser Refresh deutlich schneller als der erste Vollkatalog-Import sein.
2. App prüfen: `Aktuelles`, `Live TV` und ServusTV-Kategorien müssen erscheinen.
3. Kategorien und Sendungszuordnung stichprobenartig mit ServusTV vergleichen.
4. Bei Sendungen mit `rbtv_title_treatment` muss das Logo sichtbar sein.
5. In einer Sendungsansicht den TV-Kanal per Symbol aktivieren. Erst danach muss diese Sendung als eigener Kanal im I Launcher erscheinen; eine nicht aktivierte Sendung darf keinen eigenen Preview Channel erzeugen.
6. Den aktivierten Sendungs-Kanal öffnen und mehrere Folgen direkt starten. Bei aktuellen Folgen muss die Reihenfolge anhand `sunrise_timestamp` plausibel sein; beispielsweise soll `Servus Wetter` den tatsächlichen VOD-Verfügbarkeitszeitpunkt statt nur der Dauer anzeigen, sofern ServusTV diesen liefert.
7. Prüfen, ob I Launcher das vom Preview Program gelieferte Sendungslogo in Hero/Anreicherung übernimmt.
8. `ServusTV Live` prüfen: alle aktuell gelieferten Live-Kanäle erscheinen; laufender EPG-Titel wird angezeigt, sofern vom Guide geliefert.
9. Haupt-ServusTV-Live und mindestens einen digitalen Live-Kanal starten.
10. Bildqualität, A/V-Sync und D-Pad-Player regressionsprüfen.

### Android-Handy

1. App öffnen: kein TvProvider-Fehler.
2. Kategorien und Sendungen müssen geladen werden.
3. Sendungsdetail per Touch öffnen: Folgen sollen erst hier on demand geladen werden; Aktuelles-/TV-Kanal-Aktionen sind kompakte Symbolbuttons.
4. Folge starten und das Gerät zwischen Hoch- und Querformat drehen; Wiedergabe und Bedienung müssen der Rotation folgen.
5. Live-Karte öffnen und Stream starten; Player-Einstellungen über das Zahnrad neben der Zeitleiste öffnen.
6. Zurück-Navigation zwischen Player, Sendung und Hauptansicht prüfen.

Dieser Ausbau gilt bis zu diesen Tests nur als kompiliert/automatisiert getestet, nicht als auf realer Hardware bestätigt.
