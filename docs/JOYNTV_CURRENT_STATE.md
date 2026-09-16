# Joyn TV – aktueller technischer Stand

> **Für neue Chats / neue Entwicklungssitzungen:** Diese Datei zuerst lesen, wenn es um `joyntv`, Mysterium, Geo-Routing, Joyn-Live-TV oder die Joyn-Integration in I Launcher geht. Das Repo ist die maßgebliche Quelle; ältere Chat-Annahmen dürfen diesen Stand nicht überschreiben.

Stand: **2026-09-16**  
Entwicklungs-Branch: **`feature/joyn-tv-client`**  
PR: **#46 – `feat: add standalone Joyn Android TV client`**

## 1. Auf realer Android-TV-Hardware bestätigter Joyn-Aufbau

Die eigenständige Joyn-TV-App funktioniert auf dem realen TCL Android TV mit Mysterium als **app-lokalem Residential-HTTP-CONNECT-Proxy**. Ein systemweiter Android-VPN-Tunnel ist für den bestätigten Standardpfad nicht nötig.

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

Am **16.09.2026** wurde die erste nahtlose Verdrahtung umgesetzt.

Grundregel:

> **Die gewählte Gigablue-Bouquetliste bleibt die alleinige sichtbare Senderliste.** Joyn fügt keine eigenen Sender hinzu. Nur vorhandene Bouquet-Sender können intern eine zweite Joyn-Wiedergabequelle erhalten.

Senderliste, Reihenfolge, Sendernummer und EPG bleiben Gigablue-/Enigma2-basiert.

### Sender-Mapping

- nur Sender des aktuell gewählten Bouquets werden betrachtet;
- Namen werden konservativ normalisiert (`HD`, `UHD`, Länderzusätze usw.);
- bekannte Schreibvarianten wie Pro7/ProSieben oder Kabel 1/Kabel Eins werden vereinheitlicht;
- **kein unscharfes Runtime-Matching** auf nur ähnliche Sender;
- explizite Länderkennung gewinnt;
- bei neutralem Namen für die aktuelle österreichische Installation: AT → CH → DE;
- Playback verwendet danach die stabile Joyn-Channel-ID.

Relevante Datei:

- `app/src/main/java/com/andreassamitsch/ilauncher/data/joyn/JoynLiveTvFallbackRepository.kt`

### Sichere Cross-App-Architektur

Die bewährte Joyn-/Mysterium-Implementierung und ihre Credentials bleiben in der eigenständigen Joyn-TV-App. I Launcher kopiert keine Mysterium-Secrets.

Joyn TV stellt dafür einen **signature-geschützten Bound Service** bereit:

- `JoynPlaybackBridgeService`;
- Permission `com.andreassamitsch.joyntv.permission.PLAYBACK_BRIDGE` mit `protectionLevel="signature"`;
- beide APKs werden mit dem permanenten Repository-Key veröffentlicht;
- Service liefert Joyn-Live-Inventar sowie aufgelöstes DASH/Widevine-Playback;
- für den Media-Pfad hält Joyn TV einen eigenen lokalen `JoynMysteriumProxyBridge` offen;
- I Launcher bekommt nur Manifest-/Lizenzdaten und dessen Loopback-Adresse, **keine Mysterium-Credentials**.

Damit bleibt Routing in I Launcher strikt getrennt:

```text
Gigablue/OpenWebif -> direkt LAN
TMDB/Updater       -> direkt
Joyn DASH + DRM    -> Media3 + eigener OkHttp-Proxy -> Joyn-Bridge -> Mysterium
```

**Kein globaler ProxySelector in I Launcher.**

Relevante Dateien:

- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynPlaybackBridgeService.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/data/joyn/JoynPlaybackBridgeClient.kt`

Die erste veröffentlichte Joyn-Version mit dieser Bridge ist **`0.1.0-dev.637`** (`sourceSha d5f8bf3904a1d229a4a284b1a1d1d6f9a90e53a4`). Der Joyn-CI-Build ist erfolgreich.

### Fallback-Auslöser

SAT bleibt primär. Für gemappte Sender wird auf Joyn gewechselt bei:

- Receiver/OpenWebif nicht erreichbar bzw. Streamauflösung schlägt fehl;
- fatalem Media3-Playbackfehler;
- bestehenden Parserfehlern erst **nach** den begrenzten SAT-Reconnects;
- ca. 5 Sekunden anhaltendem Buffering;
- echter SNR-dB unter **6,5 dB** über mehrere aufeinanderfolgende Messungen;
- wiederholt `BER > 0`;
- wiederholtem **frischem OSCam-ECM-Fehler** für die aktuelle SID.

Ein fehlender OSCam-Eintrag allein ist kein Fehler, weil der Sender FTA sein kann.

Die Empfangs-/OSCam-Messung läuft jetzt im Player-Hintergrund weiter und hängt nicht davon ab, ob das Overlay sichtbar ist.

### Verhalten nach Fallback

Nach SAT → Joyn **nicht mitten in der laufenden Sendung automatisch zurück zu SAT wechseln**. So vermeiden wir Ping-Pong und Zeitsprünge durch unterschiedliche Live-Latenzen.

Beim nächsten Senderwechsel wird SAT wieder bevorzugt.

### Circuit Breaker

Drei SAT-Fallbacks innerhalb von etwa 90 Sekunden markieren SAT für rund drei Minuten als `degraded`. Währenddessen können bereits Joyn-gemappte Bouquet-Sender direkt über Joyn starten. Sender ohne Joyn-Zuordnung bleiben SAT-basiert.

### Player

Joyn-DASH und Widevine laufen im **bestehenden I-Launcher-Media3-Player**. Die Oberfläche bleibt damit nahtlos. Im Overlay ist die aktive Quelle sichtbar, beispielsweise:

```text
Quelle · SAT · Joyn-Fallback bereit
```

bzw.

```text
Quelle · Joyn AT · Fallback
Satellit gestört · <Grund>
```

Detaillierte Architektur:

- `docs/IL_LIVETV_JOYN_FALLBACK.md`

### Bestätigungsstatus

- SAT-Signaldiagnose: **real bestätigt**
- OSCam-Diagnose: **real bestätigt**
- eigenständiger Joyn-Live-Pfad/Mysterium: **real bestätigt**
- neue Cross-App-Bouquet-Fallback-Verkettung: **implementiert; realer TV-Test noch ausständig**

Für den ersten Test die **Joyn-TV-App zuerst** auf mindestens `.637` aktualisieren, danach die passende neue I-Launcher-Version. Erst nach diesem Gerätetest die Fallback-Verkettung als real-hardware-bestätigt markieren.

## 9. Regeln für zukünftige Änderungen

- Nicht auf systemweiten VPN-Tunnel zurückbauen, solange der app-lokale Proxy stabil funktioniert.
- Mysterium-TLS-Fallback niemals globalisieren.
- Port 8080 bei bekannten EU-Superproxys nicht als einzig möglichen Transport behandeln.
- Trace-IP nicht als alleinige Geo-/Entitlement-Entscheidung verwenden.
- Nicht bei jedem CONNECT-Fehler blind Lease rotieren.
- Player-Qualitäten weiterhin dynamisch aus DASH/Media3 lesen.
- I Launcher darf durch Joyn-Fallback **nicht global geproxyt** werden.
- Gigablue-Bouquet bleibt Quelle für sichtbare Senderliste, Reihenfolge und EPG.
- Keine Joyn-only-Sender automatisch in I Launcher einschleusen.
- Kein automatischer Joyn → SAT-Rücksprung während derselben laufenden Senderauswahl.
- Netzwerk-/Fallback-Änderungen immer auf realer TV-Hardware validieren.
