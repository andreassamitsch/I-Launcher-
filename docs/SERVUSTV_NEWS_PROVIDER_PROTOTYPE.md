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

## Local First / Aktualisierung

- Bereits gefundene Episoden bleiben lokal gecacht.
- Beim Öffnen der Provider-App wird sofort aus dem Cache angezeigt und danach aktualisiert.
- WorkManager prüft bei verfügbarer Netzwerkverbindung alle 15 Minuten auf neue Ausgaben.
- `TvContract.ACTION_INITIALIZE_PROGRAMS` stößt zusätzlich eine sofortige Aktualisierung an.
- Nach einem erfolgreichen Refresh wird der Preview Channel neu publiziert; I Launcher beobachtet den TvProvider bereits per `ContentObserver` und erhält die Änderung ohne Neustart.

## Playback

Die Preview-Programme enthalten einen expliziten Intent zur `PlaybackActivity` der Provider-App. Die Activity:

1. verwendet den gecachten bzw. frisch geholten ServusTV-Session-Token,
2. baut denselben VOD-HLS-Pfad wie das Kodi-Addon,
3. prüft das Manifest,
4. verwendet bei Bedarf den `products/dynamic`-Play-Link,
5. erneuert als letzten Versuch die Session,
6. spielt den HLS-Stream direkt mit Media3 ab.

Für VOD wird das HLS-Mastermanifest an Media3 übergeben. Nach dem ersten TCL-Gerätetest wird die Trackwahl bewusst TV-orientiert konfiguriert:

- `DefaultTrackSelector.setForceHighestSupportedBitrate(true)` wählt die höchste vom Gerät unterstützte Video-/Audio-Variante, die die übrigen Constraints erfüllt. Damit wird keine feste 1080p-Untergrenze gesetzt, die bei Inhalten ohne 1080p zu fehlender Videoselektion führen könnte; liefert das Manifest 1080p oder mehr und unterstützt der Fernseher den Track, wird die höchste unterstützte Variante gewählt.
- `setTunnelingEnabled(true)` fordert den hardwaregestützten Audio-/Video-Tunnelpfad an, sofern die ausgewählte Audio-/Video-Kombination und die Renderer ihn unterstützen. Da Media3 ausdrücklich auf gerätespezifische Einschränkungen bei Tunneling hinweist, ist das Ergebnis auf dem TCL zu prüfen.
- Logcat-Tag `ServusPlayback` protokolliert nur die tatsächlich selektierte Videoauflösung/Bitrate und Audioformatdaten sowie Media3-Fehler. Stream-URL, Session-Token und andere Zugangsdaten werden nicht geloggt.

Ein fester Audio-Offset wird nicht geraten. Falls die Synchronität trotz Tunneling nicht stimmt, muss zuerst auf realer Hardware bestimmt werden, ob Audio vor- oder nacheilt und ob der Versatz konstant oder zeitabhängig ist.

Stream-URLs werden nicht in den Preview-Programmen oder im lokalen Episoden-Cache gespeichert.

## Gerätetest

Erster realer TCL-Test am 2026-08-31:

- Preview Channel lässt sich nach dem Channel-Logo-Fix erfolgreich anlegen.
- `Servus Nachrichten` wird in I Launcher als Kanal/Reihe angezeigt.
- Wiedergabe startet grundsätzlich.
- Offene Befunde dieses Builds: Video startete in sichtbar niedriger Qualität; Audio war leicht asynchron.

Nächster Test mit der Quality-/Sync-Konfiguration:

1. aktualisierte `Servus-News-Provider-debug.apk` installieren.
2. Eine vollständige 19:20-Sendung aus I Launcher starten.
3. Prüfen, ob die Wiedergabe direkt in hoher Qualität startet; bei verfügbarem 1080p soll mindestens diese Qualität genutzt werden.
4. Per `adb logcat -s ServusPlayback` die tatsächlich gewählte Videoauflösung/Bitrate kontrollieren.
5. Lippen-/Ton-Synchronität über mehrere Minuten prüfen und festhalten, ob Audio vor- oder nacheilt.
6. Falls Tunneling die Synchronität verschlechtert oder neue Wiedergabefehler erzeugt, Tunneling wieder deaktivieren und anhand der Track-/Player-Diagnose den nächsten Schritt bestimmen.
7. Nach Veröffentlichung einer neuen Ausgabe prüfen, ob diese spätestens nach dem 15-Minuten-Refresh ohne Launcher-Neustart vorne in der Reihe erscheint.
8. Netzwerk kurz trennen: gecachte Karten müssen weiter sichtbar bleiben; Playback darf nachvollziehbar fehlschlagen statt die alte HLS-URL zu persistieren.
