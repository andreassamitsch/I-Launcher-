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

Für jede Sendung wird gespeichert:

- stabile ServusTV-ID
- Titel und Beschreibung
- Kategorie
- Landscape-/Square-Artwork
- Sendungslogo aus `rbtv_title_treatment`, sofern vorhanden
- aktuelle abspielbare Folgen/Videos

Auf Android TV erhält jede Sendung mit abspielbaren Inhalten einen eigenen Preview Channel mit stabiler interner ID `servus-show:<showId>`. Das Sendungslogo wird sowohl als Kanalbranding als auch über `PreviewProgram.logoUri` an die Programme weitergegeben, sodass I Launcher es über sein vorhandenes provider-neutrales `logoUri` übernehmen kann.

Bei Sendungen mit echten `episode`-/`film`-Einträgen werden diese für den Kanal bevorzugt. Nur wenn eine Sendung keine solchen Vollinhalte liefert, dienen andere abspielbare Videos als Fallback. Pro Sendung werden aktuell höchstens 18 neueste Einträge veröffentlicht.

Die Sendungs-Collections dienen dabei nur als Discovery-/Reihenfolgequelle. Für höchstens 20 plausible aktuelle Folgen pro Sendung werden die jeweiligen `products/v5.3/.../<contentId>`-Details nachgeladen, weil erst dort der verlässliche VOD-Zeitstempel `sunrise_timestamp` durchgehend vorhanden ist. Die Detailaufrufe sind global auf sechs parallele Requests begrenzt. Bereits lokal gecachte, von ServusTV stammende `publishedAtMillis`-Werte werden bei späteren Refreshes wiederverwendet; dadurch müssen im Normalfall nur neue bzw. bislang zeitstempellose Content-IDs erneut hydratisiert werden. `start_time`, `end_time` und Uhrzeiten aus Titeln bleiben ausdrücklich EPG-/Broadcastdaten und werden nicht als Veröffentlichungszeit verwendet.

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

Für explizit zu `Aktuelles` hinzugewählte Sendungen bleibt der leichte 15-Minuten-Refresh bestehen. Bei diesen Refreshes werden vorhandene ServusTV-Verfügbarkeitszeiten aus dem lokalen Sendungs-Cache wiederverwendet und nur neue bzw. noch nicht belastbar zeitgestempelte Folgen über den Produkt-Endpunkt nachgeladen.

Der lokale Cache bleibt auch auf Smartphones ohne Android-TvProvider die primäre Datenquelle. Ein fehlender TvProvider darf Datenabruf und Standalone-App nicht scheitern lassen.

## Playback

Die bereits auf TCL bestätigte Media3-Konfiguration bleibt bestehen:

- höchste vom Gerät unterstützte HLS-Variante
- Tunneling, sofern vom Gerät unterstützt
- D-Pad links/rechts ±10 Sekunden
- OK = Play/Pause
- reduzierte untere Steuerleiste
- Touch Play/Pause und Zeitleisten-Scrubbing
- VOD beendet die PlaybackActivity bei `Player.STATE_ENDED`

## Noch ausstehender Gerätetest dieses Ausbaus

### TCL / I Launcher

1. App aktualisieren und einmal `Jetzt aktualisieren` auslösen. Bei vorhandenem, frischem Sendungskatalog muss dieser Refresh deutlich schneller als der erste Vollkatalog-Import sein.
2. App prüfen: `Aktuelles`, `Live TV` und ServusTV-Kategorien müssen erscheinen.
3. Kategorien und Sendungszuordnung stichprobenartig mit ServusTV vergleichen.
4. Bei Sendungen mit `rbtv_title_treatment` muss das Logo sichtbar sein.
5. I Launcher prüfen: bestehendes `ServusTV Aktuelles` bleibt vorhanden, zusätzlich `ServusTV Live` und eigene Sendungs-Kanäle.
6. Einen Sendungs-Kanal öffnen und mehrere Folgen direkt starten. Bei aktuellen Folgen muss die Reihenfolge anhand `sunrise_timestamp` plausibel sein; beispielsweise soll `Servus Wetter` den tatsächlichen VOD-Verfügbarkeitszeitpunkt statt nur der Dauer anzeigen, sofern ServusTV diesen liefert.
7. Prüfen, ob I Launcher das vom Preview Program gelieferte Sendungslogo in Hero/Anreicherung übernimmt.
8. `ServusTV Live` prüfen: alle aktuell gelieferten Live-Kanäle erscheinen; laufender EPG-Titel wird angezeigt, sofern vom Guide geliefert.
9. Haupt-ServusTV-Live und mindestens einen digitalen Live-Kanal starten.
10. Bildqualität, A/V-Sync und D-Pad-Player regressionsprüfen.

### Android-Handy

1. App öffnen: kein TvProvider-Fehler.
2. Kategorien und Sendungen müssen geladen werden.
3. Sendungsdetail per Touch öffnen und Folge starten.
4. Live-Karte öffnen und Stream starten.
5. Zurück-Navigation zwischen Player, Sendung und Hauptansicht prüfen.

Dieser Ausbau gilt bis zu diesen Tests nur als kompiliert/automatisiert getestet, nicht als auf realer Hardware bestätigt.
