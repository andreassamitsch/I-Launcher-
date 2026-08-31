# ServusTV Aktuelles – Standalone-App + Android-TV-Provider

## Ziel

Die kleine Kotlin-App ersetzt für ausgewählte ServusTV-Inhalte den langsamen Kodi-Startpfad. Sie funktioniert eigenständig auf Android-Handys/Tablets und veröffentlicht auf Android-TV-Geräten zusätzlich einen Preview Channel, den I Launcher provider-neutral über `TvProvider` einliest.

Unterstützte Inhalte:

- **Servus Nachrichten 19:20** – vollständige Sendung
- **Servus Nachrichten in 90 Sekunden** – mehrere aktuelle Kurz-Ausgaben pro Tag
- **Der Wegscheider** – Wochenkommentar

Alle Inhalte werden in derselben chronologisch absteigenden Liste geführt. Auf Android TV werden dieselben Einträge in **einem** Kanal `ServusTV Aktuelles` veröffentlicht.

## Datenfluss

```text
ServusTV / Red-Bull JSON API
        ↓
ServusTV Aktuelles App
        ↓
lokaler SharedPreferences-Cache
        ├──→ Standalone-Liste auf Handy / Tablet / TV
        └──→ Android TvProvider (nur wenn vorhanden)
                ↓
             I Launcher: "ServusTV Aktuelles"

Auswahl eines Eintrags
        ↓
PlaybackActivity
        ↓
Media3 / HLS
```

## API-Basis

Die API-Struktur wurde anhand des bereitgestellten Kodi-Add-ons `plugin.video.servustv_com 3.1.4+matrix.1` nachvollzogen und als eigene Kotlin-Implementierung umgesetzt. Es wurde kein Python-Code übernommen.

Verwendete Endpunkte:

- `GET https://tv-api.redbull.com/v3/session?namespace=stv&category=personal_computer&os_family=http`
- `GET https://tv-api.redbull.com/search/v5/stv/de/{market}/top_results?q=...&offset=...`
- `GET https://tv-api.redbull.com/products/v5.3/stv/de/{market}/{id}`
- `GET https://tv-api.redbull.com/collections/v5.3/stv/de/{market}/{id}?offset=...`
- `GET https://tv-api.redbull.com/products/dynamic/v5/stv/de/{market}/{id}` als Playback-Fallback
- HLS: `https://dms.redbull.tv/v5/{contentId}/{sessionToken}/playlist.m3u8?namespace=stv`
- Artwork: `https://resources.redbull.tv/{contentId}/{mediaResource}/...`

`market` wird aus der ServusTV-Session (`country_code`) übernommen und nicht fest geraten.

## Inhalts-Erkennung

Die Suche verwendet getrennte Suchpfade für `Servus Nachrichten`, `Nachrichten 19:20`, `Servus Nachrichten in 90 Sekunden` und `Der Wegscheider`. Such-/Page-/Collection-Ergebnisse werden anschließend auf Produktdetails aufgelöst.

Final akzeptiert werden nur:

### Servus Nachrichten 19:20

- Nachrichten-Kontext + `19:20`
- mindestens fünf Minuten Laufzeit
- keine `90 Sekunden`, Kurzmeldung oder Newsflash
- nicht explizit als nicht abspielbar markiert

### Servus Nachrichten in 90 Sekunden

- eindeutiger Serien-/Titelkontext `Servus Nachrichten in 90 Sekunden`
- 45 Sekunden bis maximal fünf Minuten Laufzeit
- nicht explizit als nicht abspielbar markiert

Normale 1–3-Minuten-Einzelbeiträge der `Servus Nachrichten` werden dadurch **nicht** versehentlich aufgenommen.

### Der Wegscheider

- eindeutiger `Wegscheider`-Kontext
- mindestens vier Minuten Laufzeit
- nicht explizit als nicht abspielbar markiert

## Chronologie und Dubletten

Primäre Zeitquelle ist `sunrise_timestamp` der ServusTV-API. Fehlt dieser Wert, werden sichtbare Datums-/Uhrzeitangaben aus Titel, Untertitel oder Beschreibung ausgewertet.

Die API kann denselben Inhalt über Suche und Collections mit unterschiedlichen Content-IDs liefern. Deshalb wird zusätzlich fachlich dedupliziert:

- 19:20: eine Vollausgabe pro Kalendertag
- 90 Sekunden: Titel + Kalendertag + Veröffentlichungsminute; dadurch bleiben mehrere Updates desselben Tages erhalten
- Wegscheider: Titel + Kalendertag

Danach wird global nach Veröffentlichungszeit absteigend sortiert.

## Local First und Handy-Betrieb

Der lokale Cache ist die primäre App-Datenquelle. Beim Öffnen wird sofort der Cache dargestellt und anschließend aktualisiert.

Wichtig für normale Android-Geräte: `content://android.media.tv/channel` existiert typischerweise nur auf Android-TV-Systemen. Deshalb prüft `ServusChannelPublisher` vor jedem TvProvider-Zugriff, ob die Authority vorhanden ist.

Reihenfolge eines Refreshs:

1. ServusTV-Daten laden und normalisieren.
2. Lokalen Cache erfolgreich speichern.
3. Nur wenn ein Android-TvProvider vorhanden ist: Preview Channel synchronisieren.

Ein fehlender oder gerätespezifisch nicht nutzbarer TV-Provider darf den Standalone-Refresh nicht mehr scheitern lassen.

Die Standalone-Oberfläche zeigt:

- 16:9-Vorschaubild
- Formatname
- Sendungstitel
- Veröffentlichungsdatum/-zeit
- Laufzeit

Vorschaubild, Formatname, Titel und komplette Karte starten die Sendung. Für das CDN wird WebP angefordert, wie es ServusTV On selbst aktuell ebenfalls verwendet; das ist über die gesamte minSdk-Spanne besser nutzbar als AVIF.

## Android-TV-Kanal

Die interne Kanal-ID `servus-news-19-20` bleibt absichtlich bestehen, damit eine bereits gespeicherte I-Launcher-Kanalpräferenz stabil bleibt. Sichtbarer Name und Beschreibung werden auf den erweiterten Inhalt aktualisiert:

- Kanalname: `ServusTV Aktuelles`
- Inhalt: 19:20 + 90 Sekunden + Der Wegscheider
- Reihenfolge: Veröffentlichungszeit absteigend

## Playback

Die PlaybackActivity:

1. verwendet den gecachten bzw. frisch geholten Session-Token,
2. baut den VOD-HLS-Pfad,
3. prüft das Manifest,
4. verwendet bei Bedarf den `products/dynamic`-Play-Link,
5. erneuert als letzten Versuch die Session,
6. spielt direkt mit Media3 ab.

Für VOD wird das HLS-Mastermanifest an Media3 übergeben:

- `DefaultTrackSelector.setForceHighestSupportedBitrate(true)` wählt die höchste unterstützte Variante.
- `setTunnelingEnabled(true)` fordert den hardwaregestützten A/V-Tunnelpfad an.
- Der reale TCL-Test vom 2026-08-31 bestätigte damit scharfes Bild und saubere Audio-/Video-Synchronität.

### TV-Steuerung

- D-Pad links: 10 Sekunden zurück
- D-Pad rechts: 10 Sekunden vor
- OK / Play-Pause: pausieren / fortsetzen
- Hoch / Runter: reduzierte Leiste einblenden und `Einstellungen` fokussieren
- nur Zeit, Zeitleiste und Einstellungen
- nur unterer transparenter Verlauf, keine vollflächige Abdunklung
- automatische Ausblendung nach Inaktivität

### Touch

Für Standalone-Nutzung auf dem Handy:

- Tippen auf das Video: Play/Pause
- Zeitleiste ist direkt per Touch verschiebbar
- Einstellungen bleiben antippbar

### Sendungsende

Bei `Player.STATE_ENDED` beendet sich die PlaybackActivity automatisch und kehrt zur vorherigen App-/Launcher-Ansicht zurück.

Stream-URLs werden weder im lokalen Inhaltscache noch in Preview Programs gespeichert oder geloggt.

## Hintergrundaktualisierung

- WorkManager prüft bei Netzwerkverbindung alle 15 Minuten.
- `TvContract.ACTION_INITIALIZE_PROGRAMS` kann auf TV-Geräten zusätzlich einen Refresh anstoßen.
- I Launcher beobachtet den TvProvider per `ContentObserver`; neue Programme benötigen keinen Launcher-Neustart.

## Noch ausstehender Gerätetest

### TCL / I Launcher

1. Provider aktualisieren und `Jetzt aktualisieren` auslösen.
2. `ServusTV Aktuelles` muss 19:20, 90 Sekunden und Wegscheider gemischt und chronologisch zeigen.
3. Keine Dublette derselben konkreten Ausgabe.
4. Je einen Eintrag jedes Typs starten.
5. Qualität und A/V-Sync regressionsprüfen.
6. D-Pad-Steuerung, Settings und automatische Overlay-Ausblendung prüfen.
7. Eine kurze 90-Sekunden-Ausgabe bis zum Ende laufen lassen: Player muss automatisch zurückkehren.

### Android-Handy

1. App normal über den App-Launcher öffnen.
2. Kein Fehler `Unknown URL content://android.media.tv/channel`.
3. Standalone-Liste muss ohne TvProvider laden.
4. Vorschaubild und Titel müssen per Touch starten.
5. Play/Pause-Tap und Scrubbing über die Zeitleiste prüfen.
6. Am Sendungsende muss die App zur Liste zurückkehren.
