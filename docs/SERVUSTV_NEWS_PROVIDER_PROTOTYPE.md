# Servus Nachrichten Provider – Prototyp

## Ziel

Der Prototyp ersetzt für ServusTV-Nachrichten den langsamen Kodi-Startpfad durch eine kleine eigenständige Android-TV-App. Sie veröffentlicht die jeweils letzten vollständigen Ausgaben der **Servus Nachrichten 19:20** als Android-TV-Preview-Channel. I Launcher liest diesen Kanal bereits provider-neutral über `TvProvider` ein.

Datenfluss:

```text
ServusTV / Red-Bull JSON API
        ↓
Servus News Provider
        ↓
lokaler SharedPreferences-Cache
        ↓
Android TvProvider Preview Channel
        ↓
I Launcher: "Servus Nachrichten"
        ↓
Intent → Provider PlaybackActivity → Media3/HLS
```

## API-Basis

Die API-Struktur wurde anhand des bereitgestellten Kodi-Add-ons `plugin.video.servustv_com 3.1.4+matrix.1` nachvollzogen und als eigene Kotlin-Implementierung umgesetzt. Es wurde kein Python-Code in den Android-Prototyp kopiert.

Verwendete Endpunkte:

- `GET https://tv-api.redbull.com/v3/session?namespace=stv&category=personal_computer&os_family=http`
- `GET https://tv-api.redbull.com/search/v5/stv/de/{market}/top_results?q=...&offset=...`
- `GET https://tv-api.redbull.com/products/v5.3/stv/de/{market}/{id}`
- `GET https://tv-api.redbull.com/collections/v5.3/stv/de/{market}/{id}?offset=...`
- `GET https://tv-api.redbull.com/products/dynamic/v5/stv/de/{market}/{id}` als Playback-Fallback
- HLS: `https://dms.redbull.tv/v5/{contentId}/{sessionToken}/playlist.m3u8?namespace=stv`
- Artwork: `https://resources.redbull.tv/{contentId}/{mediaResource}/...`

`market` wird aus der ServusTV-Session (`country_code`) übernommen und nicht fest geraten.

## Erkennung der vollen Nachrichtensendung

Der Prototyp sucht sowohl nach `Servus Nachrichten` als auch `Nachrichten 19:20`, löst gefundene Videos auf Produktdetails auf und akzeptiert nur Einträge, die:

- zu Servus/Nachrichten gehören,
- `19:20` im Titel-/Sendungskontext enthalten,
- mindestens fünf Minuten Laufzeit besitzen,
- nicht als `90 Sekunden`, Kurzmeldung oder Newsflash markiert sind,
- nicht explizit als nicht abspielbar geliefert werden.

Damit sollen Einzelclips und Kurzformate nicht als volle Sendung im Launcher-Kanal landen.

ServusTV kann dieselbe 19:20-Ausgabe über mehrere Such-/Collection-Pfade mit unterschiedlichen Content-IDs liefern. Deshalb wird im Provider nicht nur nach API-ID dedupliziert, sondern anschließend nach der fachlichen Identität **19:20-Ausgabe + Kalendertag**. Pro Tag wird nur eine Ausgabe veröffentlicht; bei mehreren Kandidaten gewinnt der zeitlich neuere bzw. längere Kandidat.

## Local First / Aktualisierung

- Bereits gefundene Episoden bleiben lokal gecacht.
- Beim Öffnen der Provider-App wird sofort aus dem Cache angezeigt und danach aktualisiert.
- WorkManager prüft bei verfügbarer Netzwerkverbindung alle 15 Minuten auf neue Ausgaben.
- `TvContract.ACTION_INITIALIZE_PROGRAMS` stößt zusätzlich eine sofortige Aktualisierung an.
- Nach einem erfolgreichen Refresh wird der Preview Channel neu publiziert; I Launcher beobachtet den TvProvider bereits per `ContentObserver` und erhält die Änderung ohne Neustart.
- Falls durch ältere Prototypstände mehrere TvProvider-Kanäle mit derselben internen Servus-Kanal-ID existieren, behält der Publisher einen Kanal und entfernt die überzähligen.

## Playback

Die Preview-Programme enthalten einen expliziten Intent zur `PlaybackActivity` der Provider-App. Die Activity:

1. verwendet den gecachten bzw. frisch geholten ServusTV-Session-Token,
2. baut denselben VOD-HLS-Pfad wie das Kodi-Addon,
3. prüft das Manifest,
4. verwendet bei Bedarf den `products/dynamic`-Play-Link,
5. erneuert als letzten Versuch die Session,
6. spielt den HLS-Stream direkt mit Media3 ab.

Für VOD wird das HLS-Mastermanifest an Media3 übergeben. Die Trackwahl ist TV-orientiert konfiguriert:

- `DefaultTrackSelector.setForceHighestSupportedBitrate(true)` wählt die höchste vom Gerät unterstützte Video-/Audio-Variante, die die übrigen Constraints erfüllt. Damit wird keine feste 1080p-Untergrenze gesetzt, die bei Inhalten ohne 1080p zu fehlender Videoselektion führen könnte; liefert das Manifest 1080p oder mehr und unterstützt der Fernseher den Track, wird die höchste unterstützte Variante gewählt.
- `setTunnelingEnabled(true)` fordert den hardwaregestützten Audio-/Video-Tunnelpfad an, sofern die ausgewählte Audio-/Video-Kombination und die Renderer ihn unterstützen.
- Logcat-Tag `ServusPlayback` protokolliert nur die tatsächlich selektierte Videoauflösung/Bitrate und Audioformatdaten sowie Media3-Fehler. Stream-URL, Session-Token und andere Zugangsdaten werden nicht geloggt.

Der reale TCL-Test vom 2026-08-31 bestätigt mit dieser Konfiguration **scharfes Bild und saubere Audio-/Video-Synchronität**. Deshalb bleiben Trackwahl und Tunneling für den folgenden Player-UX-Pass unverändert.

### TV-Player-Steuerung

Der Media3-Standardcontroller wird im Provider nicht verwendet. Die Playback-Activity besitzt einen reduzierten TV-Controller:

- D-Pad links: 10 Sekunden zurück
- D-Pad rechts: 10 Sekunden vor
- OK / Play-Pause: Wiedergabe pausieren bzw. fortsetzen
- D-Pad hoch/runter: reduzierte Steuerleiste einblenden und `Einstellungen` fokussieren
- nur Zeitangabe, Zeitleiste und Einstellungen in der unteren Steuerleiste
- keine vollflächige Abdunklung; nur ein transparenter schwarzer Verlauf hinter der unteren Zeitleiste
- Steuerleiste blendet nach kurzer Inaktivität aus
- Einstellungen zeigen aktuell ausgewählte Bild-/Audioeigenschaften und die Sprungweite, ohne Stream-URLs oder Tokens offenzulegen

Stream-URLs werden nicht in den Preview-Programmen oder im lokalen Episoden-Cache gespeichert.

## Gerätetest

Bisher auf realem TCL bestätigt:

- Preview Channel lässt sich nach dem Channel-Logo-Fix erfolgreich anlegen.
- `Servus Nachrichten` wird in I Launcher als Kanal/Reihe angezeigt.
- Wiedergabe startet direkt über Media3.
- hohe Bildqualität und Audio-/Video-Synchronität sind mit Highest-Supported-Trackwahl + Tunneling gut.

Nächster TCL-Test:

1. Provider aktualisieren und `Jetzt aktualisieren` einmal auslösen, damit der Cache neu dedupliziert und der TvProvider-Kanal neu geschrieben wird.
2. Prüfen, dass pro Datum nur eine 19:20-Ausgabe in I Launcher erscheint.
3. Prüfen, dass kein zweiter gleichnamiger Servus-Preview-Channel vorhanden ist.
4. Sendung starten: D-Pad links/rechts muss jeweils 10 Sekunden springen.
5. OK muss Play/Pause schalten.
6. Hoch/Runter muss die reduzierte Steuerleiste mit `Einstellungen` fokussieren.
7. Prüfen, dass außerhalb des unteren Verlaufs das Videobild nicht abgedunkelt wird und die Leiste automatisch ausblendet.
8. Einstellungen öffnen und per D-Pad wieder schließen; Fokus darf nicht verloren gehen.
9. Bildqualität und A/V-Sync erneut kurz gegenprüfen, damit der UI-Pass keine Wiedergaberegression verursacht.
10. Nach Veröffentlichung einer neuen Ausgabe prüfen, ob diese spätestens nach dem 15-Minuten-Refresh ohne Launcher-Neustart vorne in der Reihe erscheint.
