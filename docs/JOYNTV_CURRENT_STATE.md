# Joyn TV – aktueller technischer Stand

> **Für neue Chats / neue Entwicklungssitzungen:** Diese Datei zuerst lesen, wenn es um `joyntv`, Mysterium, Geo-Routing, Joyn-Live-TV oder die Joyn-Integration in I Launcher geht. Das Repo ist die maßgebliche Quelle; ältere Chat-Annahmen dürfen diesen Stand nicht überschreiben.

Stand: **2026-09-18**  
Entwicklungs-Branch: **`feature/joyn-tv-client`**  
PR: **#46 – `feat: add standalone Joyn Android TV client`**

## 1. Auf realer Android-TV-Hardware bestätigter Joyn-Aufbau

Die eigenständige Joyn-TV-App funktioniert auf dem realen TCL Android TV mit Mysterium als **app-lokalem Residential-HTTP-CONNECT-Proxy** für Märkte, für die Geo-Routing benötigt wird. Ein systemweiter Android-VPN-Tunnel ist für den bestätigten Standardpfad nicht nötig.

Damit gilt:

- nur der dafür konfigurierte Joyn-Verkehr wird über Mysterium geroutet;
- Android TV und andere Apps bleiben direkt verbunden;
- `JoynMysteriumProxyBridge` stellt lokal einen Loopback-CONNECT-Proxy bereit und injiziert die kurzlebigen Mysterium-Credentials zum Upstream;
- Joyn-HTTPS, DASH, DRM und CDN-Verkehr bleiben innerhalb des CONNECT-Tunnels Ende-zu-Ende verschlüsselt.

Historischer Hardware-Meilenstein: **Joyn TV `0.1.0-dev.563`** war der erste vom Benutzer explizit mit **„funktioniert jetzt“** bestätigte Build nach dem entscheidenden Proxy/TLS-Fix. Spätere Versionen ersetzen `.563`; die Nummer ist nur der Verifikationspunkt.

## 2. Mysterium-Login

Der OAuth-Token-Austausch muss wie in der offiziellen Mysterium-App erfolgen:

- `/oauth/token` mit **`application/x-www-form-urlencoded`**, nicht JSON;
- u. a. `grant_type`, `client_id`, Gerätekennung, `code_verifier`/`code` bzw. Refresh-Token;
- Geräteinformationen werden mitgesendet;
- Netzwerk-, TLS-, DNS- und Timeoutfehler dürfen nicht verschluckt werden.

Relevante Datei:

- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynMysteriumApiClient.kt`

Dieser Login-/Tokenweg ist auf dem realen TV bestätigt.

## 3. Mysterium Residential: entscheidender TLS-443-Sonderfall

Die Mysterium-Connect-Proxy-API lieferte beim realen TV-Test unter anderem:

```text
supervpn-dc-eu-01.mysterium.network:8080
```

Auf dem TCL war reproduzierbar:

```text
:8080 -> ECONNREFUSED
:443  -> TCP/TLS erreichbar, Android meldete zunächst:
         CertPathValidatorException: Trust anchor for certification path not found
```

Die funktionierende Implementierung in `JoynMysteriumProxyBridge`:

1. bei den bekannten EU-Superproxy-Hosts zuerst TLS auf 443 probieren;
2. zunächst normale Android/JVM-Zertifikatsprüfung;
3. nur bei echtem Trust-Anchor-/CertPath-Fehler;
4. nur für `supervpn-dc-eu-[0-9]+.mysterium.network`;
5. nur auf Port 443;
6. Zertifikatsgültigkeit, Kettenkonsistenz und Hostname weiterhin prüfen;
7. **keine globale Abschaltung** der TLS-Prüfung.

Relevante Dateien:

- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynMysteriumProxyBridge.kt`
- `joyntv/src/test/java/com/andreassamitsch/joyntv/JoynMysteriumProxyBridgeTest.kt`

Diesen Fallback niemals auf beliebige Hosts ausweiten.

## 4. Residential-Lease- und Routing-Regeln

- Einen Lease über mehrere CONNECTs wiederverwenden.
- Cloudflare-/Trace-IP ist nur Diagnose; die tatsächliche Joyn-Freigabe ist autoritativ.
- Nicht für jeden fehlgeschlagenen Versuch sofort neue Credentials anfordern.
- Lease erst nach wiederholten echten Route-/Transportproblemen rotieren.
- Player-/Country-Route während eines laufenden Playbacks stabil halten.

Verwandte Dateien/Dokumente:

- `JoynMysteriumProxyScanner.kt`
- `JoynMysteriumProxyBridge.kt`
- `JoynMysteriumSettings.kt`
- `JoynPlaybackRouteGuard.kt`
- `docs/MYSTERIUM_PC_RESULTS_2026-09-12.md`
- `docs/MYSTERIUM_PC_TEST.md`

## 5. Player-Qualität – bestätigt

Die Joyn-TV-App liest verfügbare Qualitätsstufen dynamisch aus den tatsächlichen Media3/DASH-Tracks. Nichts ist auf 576p o. ä. fest verdrahtet.

`Auto` kann die aktuelle Repräsentation mit Auflösung, Pixelmaßen, Bitrate und Framerate anzeigen. Eine manuelle Auswahl pinnt die gewünschte Repräsentation; `Auto` aktiviert adaptive Auswahl wieder.

Am **13.09.2026** wurde bei Sendern der ProSiebenSat.1-Gruppe Schweiz in unserer Joyn-App **1080p** als tatsächlich angeboten bestätigt.

Relevante Dateien:

- `joyntv/src/main/java/com/andreassamitsch/joyntv/PlayerActivity.kt`
- `joyntv/src/main/java/com/andreassamitsch/joyntv/PlayerQualitySelector.kt`

## 6. I Launcher SAT-Diagnose – real bestätigt

I Launcher zeigt für den aktuell laufenden Gigablue-SAT-Sender live:

- Tuner/Tunertyp;
- SNR %;
- echte SNR dB, sofern der Treiber sie liefert;
- AGC %;
- BER.

OpenWebifs Fake-`snr_db`, bei dem nur die Prozentzahl gespiegelt wird, wird nicht als echte dB-Zahl angezeigt. Bei der Ziel-Gigablue ist laut Benutzer nur **ein Tuner** aktiv.

Wichtig: SNR/AGC/BER beschreiben den RF-/Transportpfad, **nicht** den Erfolg der Entschlüsselung.

Relevante Dateien:

- `app/src/main/java/com/andreassamitsch/ilauncher/data/openwebif/OpenWebifSignalReader.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/ui/livetv/LiveTvSignalDiagnostics.kt`

## 7. I Launcher OSCam-Diagnose – real bestätigt

OSCam läuft auf der Gigablue und wird von I Launcher über das OSCam-WebIf ausgewertet.

Implementierung:

- Host wird von der Gigablue/OpenWebif-Konfiguration übernommen;
- Standard-Port **8888**, in den Live-TV-Einstellungen änderbar;
- optional OSCam-WebIf-Benutzer/Passwort;
- ungeschütztes WebIf, HTTP Basic und HTTP Digest;
- `oscamapi.json?part=status`;
- Zuordnung über SID aus Enigma2-ServiceReference ↔ OSCam `srvid`;
- Anzeige von CAID, PROVID, Reader/Antwort, ECM-Zeit und Fehlern;
- bekannte Fehler wie `timeout`, `not found`, `no card`, `disabled`, `stopped`, `invalid`, `corrupt` werden explizit erkannt;
- keine passende ECM wird neutral als FTA/noch keine Anfrage behandelt.

Beispiel:

```text
OSCam ✓ · CAID 0D95 · PROVID 000004 · Reader localcard · ECM 287 ms
```

Der Benutzer hat am **16.09.2026** bestätigt: **„oscsm zeile funktioniert.“** Damit sind OSCam-WebIf-Zugriff, SID-Korrelation und Live-Anzeige auf der realen Anlage bestätigt.

Relevante Dateien:

- `app/src/main/java/com/andreassamitsch/ilauncher/data/oscam/OscamStore.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/data/oscam/OscamStatusReader.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/ui/livetv/LiveTvSignalDiagnostics.kt`

## 8. I Launcher: Bouquet-spezifischer SAT → Joyn-Fallback

Grundregel:

> **SAT/Gigablue ist immer die bevorzugte Quelle. Joyn ist Ersatzquelle und darf SAT nicht dauerhaft verdrängen.**

Die gewählte Gigablue-Bouquetliste bleibt die alleinige sichtbare Senderliste. Joyn fügt keine eigenen Sender hinzu. Senderliste, Reihenfolge, Sendernummer und EPG bleiben Gigablue-/Enigma2-basiert.

### Sender-Mapping und Länderpräferenz

Das Matching bleibt konservativ und basiert auf exakten normalisierten Senderfamilien. Keine unscharfen Laufzeit-Treffer.

Für **jede exakt gematchte Senderfamilie** gilt jetzt bewusst **CH → AT → DE**, sofern Joyn denselben Sender im Schweizer Inventar anbietet. Regionale Namenszusätze wie `Austria`, `Österreich`, `Schweiz`, `CH`, `DE`, `HD` oder `UHD` werden für die Senderfamilie entfernt. Damit darf z. B. ein Bouquet-Eintrag `TLC Austria HD` auf `TLC Schweiz` aus Joyn CH gemappt werden.

Grund: Auf realer Hardware wurde bei Schweizer Joyn-Feeds die beste verfügbare Qualität beobachtet, unter anderem bis **1080p** bei der ProSiebenSat.1-Gruppe. Die Länderkennung des Bouquet-Senders ist daher kein Qualitätskriterium mehr; entscheidend bleibt nur der **exakte kanonische Senderfamilien-Treffer**. Existiert kein CH-Treffer, folgt AT und danach DE.

Relevante Datei:

- `app/src/main/java/com/andreassamitsch/ilauncher/data/joyn/JoynLiveTvFallbackRepository.kt`

### Sichere Cross-App-Architektur und Markt-Routing

Die Joyn-/Mysterium-Credentials bleiben ausschließlich in der eigenständigen Joyn-TV-App. `JoynPlaybackBridgeService` ist signaturgeschützt und liefert I Launcher nur Senderinventar, Manifest-/Lizenzdaten sowie eine Loopback-Adresse.

Für die österreichische Zielinstallation gilt:

```text
Joyn AT -> DIREKT, kein Mysterium-Gateway
Joyn CH -> Mysterium Residential
Joyn DE -> Mysterium Residential
```

AT darf nicht von einem gespeicherten Mysterium-Lease abhängig gemacht werden. CH/DE verwenden den bewährten Mysterium-Residential-Pfad. I Launcher installiert dabei **keinen globalen ProxySelector**.

Relevante Dateien:

- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynPlaybackBridgeService.kt`
- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynDirectProxyBridge.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/data/joyn/JoynPlaybackBridgeClient.kt`

Die erste Joyn-TV-Version mit direktem AT-Fallback ohne Mysterium-Zwang ist **`0.1.0-dev.641`**.

### Bedienung im Live-TV-Player

Normales OK öffnet weiterhin die angeheftete Live-TV-Übersicht. Dort gibt es jetzt zusätzlich:

- **`Auto-Fallback: EIN/AUS`** – persistent gespeichert;
- **`Zu Joyn <Land>`** – manuelle Umschaltung, wenn der Bouquet-Sender gemappt ist;
- **`Zu SAT`** – jederzeit manuell zurück zum Gigablue-Stream.

Wird manuell **SAT** gewählt, bleibt dieser Sender für die aktuelle Senderauswahl auf SAT; die Automatik darf ihn nicht sofort wieder auf Joyn schieben. Beim nächsten Zappen beginnt der neue Sender wieder mit SAT als Primärquelle.

### Konservative Automatik

Die frühere aggressive Logik wurde zurückgenommen. Insbesondere wird SAT nicht mehr wegen eines einzelnen schwachen SNR-Werts, einzelner BER-Ticks oder eines globalen Circuit-Breakers übersprungen.

Aktuell gilt:

- Automatik kann im Player vollständig ausgeschaltet werden;
- manueller SAT-Modus unterdrückt Automatik für die aktuelle Senderauswahl;
- OSCam muss **5 Sekunden durchgehend** einen frischen echten ECM-Fehler melden; ein erfolgreicher/gesunder Zwischenwert setzt das Fenster zurück;
- RF-basiert wird nur umgeschaltet, wenn **echte SNR < 6,0 dB UND BER > 0 gleichzeitig** ungefähr fünf volle Sekunden anhalten;
- bloß niedrige SNR ohne Bitfehler löst keinen Wechsel aus;
- bloße BER-Ticks bei gutem SNR lösen keinen Wechsel aus;
- Media3-Buffering muss ungefähr **8 Sekunden** anhalten;
- Parserfehler erhalten die bestehenden SAT-Reconnects; innerhalb der ersten fünf Sekunden wird SAT zusätzlich bevorzugt und nicht vorschnell aufgegeben;
- harte Receiver-/Stream-/Playbackfehler können weiterhin Joyn auslösen, aber nur wenn Auto-Fallback aktiv und kein manueller SAT-Override gesetzt ist.

Der alte Circuit Breaker wird vom Player nicht mehr verwendet, um neue Sender direkt auf Joyn zu starten. **Jeder frische Senderwechsel beginnt wieder mit SAT.**

### Schneller erster Joyn-Wechsel

Sobald das Joyn-Mapping für den aktuell laufenden Bouquet-Sender bekannt ist, bereitet I Launcher dessen Joyn-Playback **im Hintergrund vor, während SAT weiterläuft**. Dabei werden die konkrete Joyn-Channel-ID, Entitlement/Manifest/DRM-Daten und der zugehörige Loopback-Bridge-Pfad für diese Sender-Session gehalten.

Dadurch muss beim tatsächlichen manuellen oder automatischen Umschalten nicht erst der komplette Joyn-/Mysterium-Pfad aufgebaut werden. Beim Zappen wird die Vorbereitung der alten Sender-Session verworfen und der neue SAT-Sender erhält seine eigene Vorbereitung.

Relevante Dateien:

- `app/src/main/java/com/andreassamitsch/ilauncher/data/joyn/JoynLiveTvFallbackRepository.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/data/livetv/LiveTvJoynFallbackStore.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/data/livetv/LiveTvReceptionMonitor.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/ui/livetv/LiveTvPlayerScreen.kt`

### Build- und Teststand

Die neue SAT-first-/Manual-/Prewarm-Logik ist ab Source-SHA **`7cc2b958fb4ce1e276e78f8a81dc23fa5f223a31`** enthalten. Der erste erfolgreich gebaute und im I-Launcher-Updater veröffentlichte Build dieses Stands ist **`0.1.0-dev.524`**. Unit-Tests und APK-Build waren erfolgreich.

Der Benutzer hat am **17.09.2026** den überarbeiteten Live-TV-Stand nach dem Einbau der manuellen SAT/Joyn-Umschaltung und der Auto-Fallback-Steuerung mit **„funktioniert“** bestätigt. Damit gelten diese Bedienpfade auf dem TCL als funktional bestätigt. Die genaue Zeitersparnis des Joyn-Prewarm wurde noch nicht separat gemessen.

Detaillierte Architektur:

- `docs/IL_LIVETV_JOYN_FALLBACK.md`

## 9. Standalone Joyn: Start-/Live-Performance und Favoriten

Am **17.09.2026** wurde die Ursache für den langsamen Aufbau der Joyn-Startseite und der Live-TV-Senderliste im Code identifiziert.

### Ursache

Beim bisherigen Start liefen zwei teure Datenpfade praktisch gleichzeitig an:

1. `JoynHomeScreen` startete sofort den kompletten AT/DE/CH-Live-TV-Ladevorgang;
2. parallel wollte die Startseite `/neu-beliebt` laden;
3. beide Pfade wurden in `JoynRepository` über denselben `networkOperationMutex` serialisiert;
4. der Live-TV-Pfad wechselte AT/DE/CH nacheinander und lud das aktuell ausgewählte Land absichtlich **zuletzt**, damit die Prozessroute am Ende wieder stimmte;
5. die Startseite führte für `/neu-beliebt` zusätzlich `loadCatalogue`, `loadMediaLibraries` und `loadCategories` **nacheinander** aus;
6. für die Joyn-Metadaten gab es keinen lokalen Startseiten-/Live-Inventar-Cache.

Damit konnte die Startseite auf den vollständigen mehrländrigen Live-TV-Pfad warten, obwohl dessen Daten für den ersten Bildaufbau gar nicht benötigt wurden.

### Umsetzung

Ab dem neuen Stand gilt:

- die normale **Startseite startet nicht mehr ungefragt den vollständigen AT/DE/CH-Live-Refresh**;
- `JoynHomeCache` speichert Startseiten-Kataloge und das letzte vollständige Live-TV-Inventar lokal;
- vorhandene Cache-Daten werden **sofort angezeigt** und anschließend im Hintergrund aktualisiert (`stale while revalidate`);
- bei `/neu-beliebt` laufen Basiskatalog, Media-Libraries und Kategorien auf derselben stabilen Route **parallel statt seriell**;
- beim Live-TV-Refresh wird das aktuell ausgewählte Land **zuerst** geladen;
- nach jedem erfolgreichen Land kann die UI bereits einen Teilstand anzeigen, statt auf alle drei Länder zu warten;
- AT/DE/CH bleiben wegen der prozessweiten Joyn-Route bewusst **seriell** – sie dürfen nicht blind parallelisiert werden;
- nach dem vollständigen Multi-Country-Refresh wird die vom Benutzer ausgewählte Länderroute wiederhergestellt;
- der vollständige Live-Stand wird anschließend wieder lokal gecacht.

Erwartetes Verhalten:

- **erster Start nach Neuinstallation:** noch kein Cache vorhanden, aber die Startseite wartet nicht mehr auf den kompletten Live-TV-Ladevorgang und die Landing-Requests sind parallelisiert;
- **ab dem zweiten Start:** Startseite kann sofort aus dem Cache zeichnen und aktualisiert danach im Hintergrund;
- **Live TV beim ersten Öffnen:** das lokale/ausgewählte Land erscheint zuerst, weitere Länder kommen schrittweise hinzu;
- **spätere Live-TV-Aufrufe:** das zuletzt bekannte vollständige Inventar steht sofort aus dem Cache zur Verfügung.

### Favoriten

Die Standalone-Joyn-App besitzt jetzt zusätzlich eine persistente **Live-TV-Favoritenliste**:

- neuer Hauptpunkt **`Favoriten`**;
- beim fokussierten Live-Sender erscheint im Hero **`☆ Zu Favoriten`** bzw. **`★ Favorit entfernen`**;
- Favoriten werden lokal anhand der stabilen Joyn-Live-ID inklusive Markt (`AT/DE/CH`) gespeichert;
- favorisierte Sender tragen auf der Live-Karte einen Stern;
- die Favoritenliste zeigt ausschließlich die ausgewählten Live-Sender in der normalen Joyn-Sortierung.

Relevante Dateien:

- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynHomeScreen.kt`
- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynHomeCache.kt`
- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynRepository.kt`

Build-Stand: **Joyn TV `0.1.0-dev.645`**, Source-SHA `69e5343d65627ae66e475df731e1a494684be554`. `:joyntv:testDebugUnitTest`, signierter Release-Build, Signaturprüfung und Veröffentlichung in `joyn-downloads` waren erfolgreich. Die tatsächliche Startzeitverbesserung und Favoritenbedienung müssen noch auf dem realen TCL bestätigt werden.


### Weiterschauen / Android TV Watch Next

Joyn TV speichert Wiedergabefortschritt lokal und synchronisiert ihn bei angemeldetem Konto zusätzlich über Joyns Resume-API. I Launcher liest Androids `TvContract.WatchNextPrograms` und kann daher von Apps veröffentlichte Watch-Next-Einträge direkt in seiner eigenen **Weiterschauen**-Zeile anzeigen.

Am **18.09.2026** wurde ein Resume-Fehler korrigiert: Joyns Resume-Asset-ID und die für Playback verwendete Video-ID werden nun getrennt behandelt. Beim Öffnen aus Joyn-`Weiterschauen` oder über den Android-TV-Watch-Next-Deep-Link wird die explizit gespeicherte Position an den Player übergeben und Fortschritt weiter unter der Resume-Asset-ID gespeichert.

Aktuell veröffentlicht Joyn TV dort **unfertige Inhalte** als `WATCH_NEXT_TYPE_CONTINUE`. Eine erst später neu veröffentlichte Folge einer bereits fertig angesehenen Serie wird noch **nicht automatisch** als `NEXT`/`NEW` erzeugt; Android TV entdeckt neue Joyn-Folgen nicht selbst, sondern benötigt dafür einen von der App veröffentlichten Watch-Next-Eintrag.

## 10. Regeln für zukünftige Änderungen

- SAT/Gigablue bleibt die bevorzugte Quelle; Joyn bleibt Fallback.
- Jeder neue Senderwechsel beginnt mit SAT.
- Benutzer muss Auto-Fallback jederzeit im Player deaktivieren sowie manuell zwischen SAT und Joyn wechseln können.
- Manueller SAT-Override darf nicht sofort von der Automatik überschrieben werden.
- Mysterium-TLS-Fallback niemals globalisieren.
- Nicht auf systemweiten VPN-Tunnel zurückbauen, solange der app-lokale Proxy stabil funktioniert.
- Für die österreichische Zielinstallation Joyn AT direkt abspielen; kein Mysterium-Gateway erzwingen.
- CH/DE-Geo-Routing weiter über den bewährten Mysterium-Residential-Pfad führen, solange dies dort erforderlich ist.
- Bei jedem exakten Joyn-Senderfamilien-Match CH vor AT und DE bevorzugen, sofern ein CH-Kanal vorhanden ist; regionale Namenszusätze nicht als Gegenargument verwenden.
- Gigablue-Bouquet bleibt Quelle für sichtbare Senderliste, Reihenfolge und EPG.
- Keine Joyn-only-Sender automatisch in I Launcher einschleusen.
- I Launcher darf durch Joyn-Fallback nicht global geproxyt werden.
- Standalone-Startseite nicht wieder an einen vollständigen Multi-Country-Live-Refresh koppeln.
- Multi-Country-Live-Routen in der Standalone-App nicht parallelisieren, solange die Route prozessweit ist; stattdessen Cache und progressive Darstellung verwenden.
- Netzwerk-/Fallback-Änderungen immer auf realer TV-Hardware validieren.
