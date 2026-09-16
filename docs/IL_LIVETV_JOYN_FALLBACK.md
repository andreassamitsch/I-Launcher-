# I Launcher Live TV – SAT → Joyn Fallback

Stand: 2026-09-16  
Branch: `feature/joyn-tv-client`

## Zielbild

Die sichtbare Senderliste, Reihenfolge und der EPG bleiben vollständig durch das **aktuell gewählte Gigablue/Enigma2-Bouquet** bestimmt. Joyn erzeugt keine zusätzlichen Sender in I Launcher.

Ein Bouquet-Sender kann intern zwei Wiedergabequellen besitzen:

1. **Primär:** Gigablue/OpenWebif/SAT
2. **Fallback:** passender Joyn-Live-Sender

Für den Benutzer bleibt es derselbe Sender im selben I-Launcher-Player.

## Senderzuordnung

Nur Sender aus dem aktuell an den Player übergebenen Bouquet werden gegen die Joyn-Live-Liste gematcht.

Die Zuordnung erfolgt konservativ:

- Sendernamen werden normalisiert (z. B. `HD`, `UHD`, Länderzusätze);
- bekannte Schreibweisen wie `Pro7`/`ProSieben` oder `Kabel 1`/`Kabel Eins` werden vereinheitlicht;
- **kein unscharfes Runtime-Matching** für fast ähnliche Sender;
- explizite Länderzusätze gewinnen (`Austria` → AT, `Schweiz` → CH, `Deutschland` → DE);
- ohne Länderzusatz ist für die aktuelle österreichische Installation die Reihenfolge AT → CH → DE;
- die endgültige Zuordnung basiert auf stabilen Joyn-Channel-IDs, nicht auf einem erneuten Namensvergleich beim Fallback.

Relevante Datei:

- `app/src/main/java/com/andreassamitsch/ilauncher/data/joyn/JoynLiveTvFallbackRepository.kt`

## Sichere Verbindung zur Standalone-Joyn-App

Die funktionierende Joyn-/Mysterium-Implementierung bleibt in der eigenständigen `joyntv`-APK. I Launcher kopiert insbesondere **keine Mysterium-Credentials** in seine eigenen Preferences.

Joyn TV stellt dafür `JoynPlaybackBridgeService` bereit:

- Android Bound Service / Messenger;
- Zugriff nur mit `com.andreassamitsch.joyntv.permission.PLAYBACK_BRIDGE`;
- Permission-Schutzlevel `signature`;
- beide APKs werden mit dem permanenten Repository-Key signiert;
- liefert die Joyn-Live-Senderliste und für einen konkreten Fallback das aufgelöste DASH-/Widevine-Playback;
- Mysterium-Benutzername/Passwort des Residential-Leases verlassen die Joyn-App nicht.

### Routing nach Joyn-Markt

Für die aktuelle österreichische Installation gilt ausdrücklich:

```text
Joyn AT -> direkt, kein Mysterium-Gateway nötig
Joyn CH -> Mysterium Residential
Joyn DE -> Mysterium Residential
```

Der erste reale Fallback-Test mit **PULS 4 HD Austria** hat gezeigt, dass das Bouquet-Mapping und der automatische OSCam-Fehler-Fallback bereits bis zur Joyn-Bridge funktionieren. Die erste Bridge-Version verlangte danach fälschlich auch für AT einen aktiven Mysterium-Residential-Proxy. Diese Annahme ist korrigiert.

Für AT löst `JoynPlaybackBridgeService` Entitlement/Playlist jetzt explizit über die direkte österreichische Internetverbindung auf. Ein eventuell noch aktiver app-spezifischer Mysterium-WireGuard-Tunnel wird vorher beendet. Für den Media-Pfad bleibt das bestehende sichere Cross-App-Protokoll unverändert: I Launcher erhält einen zufälligen Loopback-Port. Dahinter liegt bei AT nun `JoynDirectProxyBridge`, ein lokaler CONNECT-Relay, der direkt zum Joyn/CDN-Ziel verbindet. Es gibt **keinen externen Proxy/Gateway** und keine Mysterium-Credentials im AT-Pfad.

Für CH/DE bleibt `JoynMysteriumProxyBridge` unverändert zuständig und verwendet den jeweils vorbereiteten Residential-Lease.

Damit gilt in I Launcher:

```text
OpenWebif / Gigablue -> direkt im LAN
TMDB / Updates       -> direkt
Joyn AT DASH + DRM   -> Media3 -> lokaler Direct-Relay -> Internet direkt
Joyn CH/DE DASH+DRM  -> Media3 -> lokaler Joyn-Bridge -> Mysterium Residential
```

Es wird **kein globaler ProxySelector für I Launcher** installiert.

Relevante Dateien:

- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynPlaybackBridgeService.kt`
- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynDirectProxyBridge.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/data/joyn/JoynPlaybackBridgeClient.kt`

## Automatischer Fallback

SAT bleibt beim normalen Senderwechsel die bevorzugte Quelle. Auf Joyn wird gewechselt, wenn für den Bouquet-Sender eine Joyn-Zuordnung verfügbar ist und der SAT-Pfad als gestört bewertet wird.

Aktuell berücksichtigte Ursachen:

- Gigablue/OpenWebif-Stream kann nicht aufgelöst bzw. verbunden werden;
- fataler Media3-Playbackfehler;
- bestehende Media3-Parserfehler bekommen weiterhin zuerst die begrenzten SAT-Reconnects;
- SAT-Stream bleibt etwa 5 Sekunden im Buffering;
- echte SNR-dB fallen unter 6,5 dB für mehrere aufeinanderfolgende Messungen;
- BER wird wiederholt größer als 0;
- OSCam meldet für die aktuelle SID wiederholt einen **frischen echten ECM-Fehler** wie `timeout`, `not found`, `no card`, `disabled` usw.

Ein fehlender OSCam-Eintrag allein löst keinen Fallback aus, weil der Sender FTA sein kann.

SNR-/BER-/OSCam-Werte werden unabhängig von der sichtbaren Overlay-Zeile im Hintergrund weiter gemessen.

Relevante Dateien:

- `app/src/main/java/com/andreassamitsch/ilauncher/data/livetv/LiveTvReceptionMonitor.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/ui/livetv/LiveTvPlayerScreen.kt`

## Verhalten nach einem Wechsel

Nach erfolgreichem SAT → Joyn-Wechsel bleibt der Sender bis zum nächsten Zappen auf Joyn. Es gibt **keinen automatischen Rücksprung mitten in der Sendung**, damit unterschiedliche Live-Latenzen nicht zu Zeit-/Bildsprüngen oder Ping-Pong führen.

Beim nächsten Senderwechsel wird grundsätzlich wieder SAT bevorzugt – außer der kurze Circuit Breaker ist aktiv.

## Circuit Breaker

Mehrere SAT-Ausfälle kurz hintereinander sprechen typischerweise für Receiver-/Wetterprobleme. Nach drei SAT-Fallbacks innerhalb von etwa 90 Sekunden wird SAT für rund drei Minuten als `degraded` behandelt.

Währenddessen können **nur bereits Joyn-gemappte Bouquet-Sender** direkt über Joyn starten. Sender ohne Joyn-Zuordnung bleiben weiterhin SAT-Sender.

## Player

Joyn wird im bestehenden I-Launcher-Media3-Player wiedergegeben:

- DASH über `media3-exoplayer-dash`;
- Widevine über Media3 DRM;
- Manifest, Lizenz und Segmente benutzen denselben app-spezifischen OkHttp-Pfad;
- bei AT endet dieser Pfad nach dem lokalen Loopback-Relay direkt im Internet;
- bei CH/DE geht er über den Mysterium-Residential-Bridge;
- Senderliste und EPG bleiben unverändert Gigablue-basiert.

Im Overlay wird die aktive Quelle sichtbar:

```text
Quelle · SAT · Joyn-Fallback bereit
```

beziehungsweise nach Übernahme:

```text
Quelle · Joyn AT · Fallback
Satellit gestört · <Grund>
```

## Build- und Bestätigungsstatus

Die erste Bridge-Version der Joyn-TV-App ist **`0.1.0-dev.637`** (`sourceSha d5f8bf3904a1d229a4a284b1a1d1d6f9a90e53a4`). Sie hat den Cross-App-Vertrag eingeführt, verlangte beim ersten realen AT-Test aber noch fälschlich Mysterium für AT.

Der AT-Direktfix liegt auf `feature/joyn-tv-client` ab Source-Commit **`2d5bc798d4ce8d971514592dd8155e264f1d3f09`**. Der dazugehörige Joyn-TV-CI-Lauf ist Run **641**; nach erfolgreicher Veröffentlichung ist die Zielversion **`0.1.0-dev.641`**.

Die erste veröffentlichte I-Launcher-Version mit Bouquet-Mapping, SAT-/OSCam-Health-Policy, Circuit Breaker und nahtlosem Media3-DASH/Widevine-Fallback ist **`0.1.0-dev.513`** (`sourceSha 06e692050d1cb909ddd0acfbd7bd491ef2ee5bce`). Unit-Tests, APK-Build und Veröffentlichung im I-Launcher-Updater waren erfolgreich.

Die Tests decken insbesondere konservatives Bouquet-Matching, Länderpräferenz, SNR-Debouncing, BER, OSCam-Fehler, Circuit Breaker und die CONNECT-Zielauswertung des neuen AT-Direkt-Relays ab.

## Realer TV-Teststand

Am 16.09.2026 wurde auf dem TCL mit **PULS 4 HD Austria** real bestätigt:

1. Bouquet-Sender wird korrekt einem Joyn-AT-Sender zugeordnet;
2. ein wiederholter echter OSCam-Entschlüsselungsfehler löst den SAT → Joyn-Wechsel aus;
3. I Launcher bleibt im selben Player und zeigt `Quelle · Joyn · Fallback`;
4. die Bridge wurde erreicht;
5. die Wiedergabe scheiterte dort ausschließlich an der falschen damaligen Voraussetzung „AT benötigt Mysterium Residential“.

Damit sind Mapping und Fallback-Auslösung real bestätigt. Die tatsächliche **AT-Direktwiedergabe nach dem Fix** wartet noch auf den nächsten Gerätetest.

## Installations-/Testreihenfolge

Für den nächsten AT-Test muss nur **Joyn TV auf den Build mit AT-Direktfix** aktualisiert werden. I Launcher `.513` kann unverändert bleiben, weil der Bridge-Vertrag (Manifest/Lizenz + Loopback-Proxyadresse) absichtlich kompatibel geblieben ist.

Danach PULS 4 HD Austria erneut starten und den SAT-/OSCam-Fehler provozieren. Erwartung: derselbe I-Launcher-Player übernimmt Joyn AT jetzt ohne Mysterium-Gateway.
