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

Für den eigentlichen Media-Pfad erzeugt Joyn TV einen dedizierten lokalen `JoynMysteriumProxyBridge`. I Launcher erhält nur dessen Loopback-Adresse sowie Manifest-/Lizenz-URL.

Damit gilt in I Launcher:

```text
OpenWebif / Gigablue -> direkt im LAN
TMDB / Updates       -> direkt
Joyn DASH + DRM      -> eigener Media3/OkHttp-Pfad -> lokaler Joyn-Bridge -> Mysterium Residential
```

Es wird **kein globaler ProxySelector für I Launcher** installiert.

Relevante Dateien:

- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynPlaybackBridgeService.kt`
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
- Manifest, Lizenz und Segmente benutzen denselben app-spezifischen OkHttp-Proxy;
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

## Installations-/Testreihenfolge

Die erste Bridge-Version der Joyn-TV-App ist `0.1.0-dev.637`. Da die Bridge und die Signature-Permission in der Joyn-App definiert werden, für den ersten Gerätetest **Joyn TV zuerst aktualisieren**, danach I Launcher.

Der SAT-/OSCam-Teil ist auf realer Hardware bestätigt. Die neue automatische SAT → Joyn-Verkettung ist zum Zeitpunkt dieser Notiz implementiert und durch CI/Unit-Tests abzusichern; sie gilt erst nach dem folgenden TV-Test als real-hardware-bestätigt.
