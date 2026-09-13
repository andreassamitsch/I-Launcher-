# Joyn TV – aktueller technischer Stand

> **Für neue Chats / neue Entwicklungssitzungen:** Diese Datei zuerst lesen, wenn es um die eigenständige `joyntv`-App, Mysterium, Geo-Routing, Live-TV oder Player-Qualität geht. Sie hält die auf realer Android-TV-Hardware bestätigten Erkenntnisse fest und soll ältere Chat-Annahmen überstimmen.

Stand: **2026-09-13**  
Entwicklungs-Branch: **`feature/joyn-tv-client`**  
PR: **#46 – `feat: add standalone Joyn Android TV client`**

## 1. Bestätigter Zielaufbau

Die Joyn-TV-App verwendet Mysterium als **app-lokalen Residential-HTTP-CONNECT-Proxy**. Es wird **kein systemweiter Android-VPN-Tunnel** benötigt.

Damit gilt:

- nur Netzwerkverkehr der Joyn-TV-App wird über den gewählten Mysterium-Proxy geroutet;
- Android TV und andere Apps bleiben direkt verbunden;
- Proxy-Routing ist der bevorzugte Weg gegenüber einem vollständigen VPN-Tunnel, weil weniger Overhead entsteht;
- `JoynMysteriumProxyBridge` stellt lokal auf Loopback einen unauthentifizierten CONNECT-Proxy bereit und injiziert die kurzlebigen Mysterium-Credentials in Richtung Upstream.

Dieser Aufbau wurde am **realen TCL Android TV** erfolgreich getestet.

## 2. Mysterium-Anmeldung – wichtiger Fix

Der Magic-Link selbst war nicht das Problem. Der anschließende OAuth-Token-Austausch musste an das Verhalten der offiziellen Mysterium-App angepasst werden.

Bestätigte Implementierung:

- `/oauth/token` mit **`application/x-www-form-urlencoded`** statt JSON;
- `grant_type`, `client_id`, `code_verifier`, `code` und Gerätekennung werden passend übertragen;
- Refresh-Token ebenfalls form-urlencoded;
- `device_id` / Android-TV-Geräteinformationen werden mitgesendet;
- Netzwerk-, TLS-, DNS- und Timeout-Fehler dürfen nicht verschluckt werden, sondern werden in der TV-Oberfläche diagnostisch ausgegeben.

Relevante Datei:

- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynMysteriumApiClient.kt`

## 3. Entscheidende Erkenntnis zum Mysterium-Residential-Proxy auf Android TV

Die Mysterium-Connect-Proxy-API lieferte beim realen TV-Test u. a.:

```text
supervpn-dc-eu-01.mysterium.network:8080
```

Das Verhalten auf dem TCL-TV war reproduzierbar:

```text
Port 8080 -> ECONNREFUSED / Connection refused
Port 443  -> TCP/TLS erreichbar, aber Android meldet:
             CertPathValidatorException: Trust anchor for certification path not found
```

Der Fehler lag damit **nicht bei Joyn und nicht bei der Mysterium-Anmeldung**, sondern zwischen der App und dem von Mysterium gelieferten EU-Superproxy.

### Funktionierende Lösung

Für bekannte Mysterium-EU-Superproxy-Hosts (`supervpn-dc-eu-XX.mysterium.network`) wird bei einem von der API als `:8080` gelieferten Lease zuerst **TLS auf Port 443** probiert.

Android vertraut der dort aktuell gelieferten privaten Zertifikatskette nicht automatisch. Deshalb besitzt `JoynMysteriumProxyBridge` einen **eng begrenzten Fallback**:

1. zuerst normale Android-/JVM-TLS-Zertifikatsprüfung;
2. nur wenn der Fehler tatsächlich ein **Trust-Anchor-/CertPath-Fehler** ist;
3. nur für das exakte Hostmuster `supervpn-dc-eu-[0-9]+.mysterium.network`;
4. nur auf **Port 443**;
5. Zertifikatsgültigkeit und Zertifikatskette werden weiterhin geprüft;
6. der Hostname wird weiterhin gegen das Zertifikat geprüft;
7. die Zertifikatsprüfung für Joyn, Manifest, DRM und Stream-CDNs wird **nicht global deaktiviert**.

Der eigentliche Joyn-HTTPS-Verkehr bleibt innerhalb des CONNECT-Tunnels Ende-zu-Ende verschlüsselt und verwendet weiterhin die normale Android-Zertifikatsprüfung.

Relevante Datei:

- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynMysteriumProxyBridge.kt`

Regressionstest:

- `joyntv/src/test/java/com/andreassamitsch/joyntv/JoynMysteriumProxyBridgeTest.kt`

**Bestätigung:** Der Benutzer hat nach Installation des Builds **`0.1.0-dev.563`** am TCL-TV bestätigt: **„funktioniert jetzt“**. Damit ist dieser Proxy-/TLS-Weg auf realer Android-TV-Hardware verifiziert.

## 4. Residential-Lease-Strategie

Ein von Mysterium gelieferter Residential-Proxy-Lease ist nicht zwingend eine dauerhaft feste öffentliche Exit-IP. Mehrere CONNECT-Verbindungen können über denselben Lease unterschiedliche öffentliche Exits verwenden.

Deshalb:

- denselben Lease für mehrere CONNECT-Versuche wiederverwenden;
- eine öffentliche Trace-IP dient nur der Diagnose;
- die tatsächliche **Joyn-Live-Freigabe** entscheidet, ob der Kandidat akzeptiert wird;
- nicht für jeden fehlgeschlagenen Joyn-/Geo-Test sofort neue Credentials anfordern;
- neuen Lease erst nach wiederholten echten Transport-/Route-Fehlern anfordern;
- API-Spam und unnötige Lease-Rotation vermeiden.

Relevante Dateien:

- `JoynMysteriumProxyScanner.kt`
- `JoynMysteriumProxyBridge.kt`
- `JoynMysteriumSettings.kt`
- `JoynPlaybackRouteGuard.kt`

Vorherige PC-Messungen und Testdetails stehen außerdem in:

- `docs/MYSTERIUM_PC_RESULTS_2026-09-12.md`
- `docs/MYSTERIUM_PC_TEST.md`

## 5. Player-Qualität – bestätigt

Der Player besitzt eine dynamische Qualitätsanzeige/-auswahl. Die verfügbaren Stufen werden **aus den tatsächlichen DASH-Tracks des jeweiligen Streams** gelesen und nicht fest codiert.

Bei `Auto` kann zusätzlich die aktuell verwendete Repräsentation angezeigt werden, inklusive z. B.:

- Auflösung / Höhe (`1080p` etc.)
- Pixelabmessungen
- Bitrate
- Framerate, sofern vorhanden

Eine manuelle Auswahl setzt die gewünschte Videoqualität fest; `Auto` aktiviert wieder adaptive Auswahl.

Relevante Dateien:

- `PlayerActivity.kt`
- `PlayerQualitySelector.kt`

### Reales Vergleichsergebnis Schweiz

Am **13.09.2026** wurde bei Sendern der **ProSiebenSat.1-Gruppe Schweiz** in unserer Joyn-App **1080p** als angebotene Streamqualität bestätigt.

Zum Vergleich zeigte blue TV Air Free auf demselben TV für VOX / ProSieben / Sat.1 manuell nur bis **576p** an. Das ist lediglich ein Vergleichswert; für unsere App maßgeblich sind die von Joyn tatsächlich gelieferten DASH-Repräsentationen.

## 6. Was bei zukünftigen Änderungen nicht wieder zurückgebaut werden soll

- Nicht wieder auf einen systemweiten VPN-Tunnel wechseln, solange der app-lokale Proxy stabil funktioniert.
- Den Mysterium-TLS-Fallback **nicht global** auf beliebige Hosts oder Zertifikate ausweiten.
- `8080` bei den bekannten EU-Superproxy-Antworten nicht als einzig möglichen Socket-Endpunkt behandeln.
- Trace-IP nicht als alleinige Entscheidung für Geo-/Joyn-Tauglichkeit verwenden.
- Bei jedem CONNECT-Fehler nicht blind neue Residential-Credentials erzeugen.
- Player-Qualitätsstufen nicht hart codieren; weiterhin dynamisch aus Media3/DASH lesen.

## 7. Schnelle Diagnose bei erneutem Proxy-Problem

Wenn ein neuer TV-Test fehlschlägt, zuerst den unteren Diagnosebereich fotografieren/loggen und unterscheiden:

```text
AUTH / TOKEN_FEHLER
  -> Mysterium OAuth / Token-Austausch prüfen

LEASE_FEHLER
  -> Mysterium Control-API / Account / Limits prüfen

ECONNREFUSED :8080
  -> bei EU-Superproxy erwartbarer Legacy-Endpunkt; TLS:443-Fallback prüfen

Trust anchor for certification path not found auf :443
  -> prüfen, ob der eng begrenzte Mysterium-private-CA-Fallback erreicht wird

TRACE_FEHLER / ROUTE_FEHLER
  -> Transport/Lease prüfen; Trace allein ist nicht autoritativ

VPN_ERKANNT / Joyn-Entitlement abgelehnt
  -> nächster CONNECT mit demselben Lease; erst bei echten Route-Fehlern Lease wechseln

Joyn Live OK
  -> Proxy aktivieren und gewählten Routing-Modus speichern
```

## 8. Letzter bestätigter Meilenstein

**`0.1.0-dev.563`** ist der erste explizit vom Benutzer bestätigte Android-TV-Build, bei dem die Mysterium-Residential-Proxy-Verbindung nach dem TLS-/Trust-Anchor-Fix funktioniert hat.

Spätere Builds dürfen natürlich eine höhere Versionsnummer haben. Die Nummer `.563` ist hier deshalb als **historischer Verifikationspunkt**, nicht als dauerhaft aktuelle Version, dokumentiert.

## 9. I Launcher: SAT-Diagnose als Grundlage für Joyn-Fallback

Ziel für I Launcher ist langfristig **ein gemeinsamer Live-TV-Sender mit mehreren Empfangsquellen**: Gigablue/Enigma2 über Satellit als Primärquelle und Joyn als nahtloser Fallback, statt zwei getrennte Live-TV-Oberflächen.

Als erster Schritt wurde am 13.09.2026 die Live-SAT-Diagnose in den bestehenden I-Launcher-Player eingebaut:

- OpenWebif-Endpunkt `api/signal` wird während Live-TV einmal pro Sekunde abgefragt;
- angezeigt werden, soweit der Gigablue-Treiber sie liefert: Tuner, Tuner-Typ, SNR %, echte SNR dB, AGC % und BER;
- OpenWebifs `snr_db`-Fallback auf die bloße SNR-Prozentzahl wird erkannt und nicht fälschlich als dB beschriftet;
- der Netzwerkclient wird zwischen Polls wiederverwendet;
- die Diagnose erscheint im bestehenden Sender-Overlay und verändert das Playback nicht.

Relevante Dateien:

- `app/src/main/java/com/andreassamitsch/ilauncher/data/openwebif/OpenWebifApi.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/data/openwebif/OpenWebifSignalReader.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/ui/livetv/LiveTvSignalDiagnostics.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/ui/livetv/LiveTvPlayerScreen.kt`

### Verschlüsselte SAT-Sender

**SNR, AGC und BER sagen nichts darüber aus, ob ein verschlüsselter Sender erfolgreich entschlüsselt wurde.** Sie beschreiben den Empfang bzw. die Tuner-/Transportqualität. Ein verschlüsselter Sender kann daher perfekte SNR-/BER-Werte haben und trotzdem wegen CI/CAM/Softcam/Entschlüsselung kein nutzbares Bild liefern.

Für den späteren automatischen SAT -> Joyn-Fallback müssen daher zwei Fehlerklassen getrennt bewertet werden:

1. **RF-/Empfangsfehler:** SNR/BER/Tunerstatus plus Media3-Buffering/Parserfehler.
2. **Entschlüsselungs-/Playbackfehler:** bei gutem RF-Signal trotzdem kein renderbares Video bzw. kein erster Videoframe/Decoderfortschritt. OpenWebif kann zusätzlich kennzeichnen, dass ein Service verschlüsselt ist (`sIsCrypted`/`crypt`), aber das allein beweist noch keine erfolgreiche Entschlüsselung.

Auf dem aktuellen Ziel-Gigablue ist laut Benutzer nur **ein Tuner** im Einsatz. Damit ist `api/signal` für die laufenden Tests wesentlich eindeutiger als in einer Mehrtuner-Konfiguration.
