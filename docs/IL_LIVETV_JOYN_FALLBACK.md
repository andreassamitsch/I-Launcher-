# I Launcher Live TV – SAT → Joyn Fallback

Stand: 2026-09-16  
Branch: `feature/joyn-tv-client`

## Zielbild

Die sichtbare Senderliste, Reihenfolge und der EPG bleiben vollständig durch das **aktuell gewählte Gigablue/Enigma2-Bouquet** bestimmt. Joyn erzeugt keine zusätzlichen Sender in I Launcher.

Ein Bouquet-Sender kann intern zwei Wiedergabequellen besitzen:

1. **Primär:** Gigablue/OpenWebif/SAT
2. **Fallback:** passender Joyn-Live-Sender

**SAT ist immer die bevorzugte Quelle.** Jeder neue Senderwechsel startet zunächst über Gigablue. Joyn soll nur übernehmen, wenn der Benutzer das manuell möchte oder die optionale Automatik einen ausreichend eindeutigen SAT-Fehler erkennt.

## Senderzuordnung

Nur Sender des aktuellen Gigablue-Bouquets werden gegen das Joyn-Inventar gematcht. Die Zuordnung bleibt konservativ und verwendet nach Normalisierung nur exakte Senderfamilien. Fast ähnliche Sender werden nicht unscharf gematcht.

Für Sender, bei denen auf realer Hardware ein Qualitätsvorteil der Schweizer Joyn-Feeds bekannt ist, gilt die Länderreihenfolge **CH → AT → DE**. Aktuell betrifft das:

- ProSieben
- SAT.1
- Kabel Eins
- ProSieben MAXX
- sixx
- SAT.1 Gold
- Kabel Eins Doku
- TLC

Für die ProSiebenSat.1-Gruppe Schweiz wurde in der Joyn-App bis **1080p** als angebotene DASH-Qualität beobachtet. Andere Sender behalten ihre normale regionale Priorität; z. B. bleibt PULS 4 Austria auf Joyn AT.

Relevante Datei:

- `app/src/main/java/com/andreassamitsch/ilauncher/data/joyn/JoynLiveTvFallbackRepository.kt`

## Markt-Routing

```text
Joyn AT -> direkt, kein Mysterium-Gateway
Joyn CH -> Mysterium Residential
Joyn DE -> Mysterium Residential
```

Joyn TV bleibt Eigentümer der Mysterium-Credentials. I Launcher kommuniziert über den signature-geschützten `JoynPlaybackBridgeService` und erhält nur Manifest-/Lizenzdaten sowie eine Loopback-Adresse.

```text
OpenWebif / Gigablue -> direkt im LAN
TMDB / Updates       -> direkt
Joyn AT DASH + DRM   -> lokaler Direct-Relay -> Internet direkt
Joyn CH/DE DASH+DRM  -> lokaler Joyn-Bridge -> Mysterium Residential
```

Es gibt keinen globalen Joyn-ProxySelector im I-Launcher-Prozess.

## Bedienung im Player

Normales OK öffnet die angeheftete Live-TV-Übersicht. Dort stehen neben EPG und Beenden jetzt zwei neue Steuerungen zur Verfügung:

- **`Auto-Fallback: EIN/AUS`** – Einstellung wird lokal gespeichert;
- **`Zu Joyn <Land>` / `Zu SAT`** – manuelle Quellenwahl für den aktuellen Sender.

Wird manuell SAT gewählt, setzt I Launcher für die aktuelle Senderauswahl einen SAT-Override. Solange der Benutzer nicht zappt, darf die Automatik diesen manuellen Wunsch nicht wieder überschreiben. Beim nächsten Senderwechsel beginnt der neue Sender erneut regulär auf SAT.

Das Ausschalten der Automatik ändert die gerade laufende Quelle nicht automatisch. Ist Joyn bereits aktiv, kann der Benutzer mit **`Zu SAT`** sofort zurückkehren.

## Konservative automatische Umschaltung

Die Automatik wurde bewusst deutlich vorsichtiger gemacht:

- Automatik greift nur bei einem vorhandenen Joyn-Mapping und wenn `Auto-Fallback` aktiv ist;
- ein manueller SAT-Override sperrt den automatischen Wechsel für die aktuelle Senderauswahl;
- **niedrige SNR allein** löst keinen Wechsel aus;
- **BER allein** löst keinen Wechsel aus;
- RF-Fallback nur bei **echter SNR < 6,0 dB UND BER > 0 gleichzeitig für ungefähr fünf volle Sekunden**;
- OSCam muss **fünf Sekunden durchgehend** einen frischen echten ECM-Fehler melden; ein gesunder/erfolgreicher Zwischenwert setzt den Timer zurück;
- Media3-Buffering muss ungefähr **acht Sekunden** anhalten;
- Parserfehler erhalten zunächst die bestehenden SAT-Reconnects und werden in der Startphase nicht vorschnell an Joyn übergeben;
- harte Receiver-/Netzwerk-/Playbackfehler können weiterhin Joyn auslösen, wenn die Automatik aktiv ist.

Der frühere Circuit-Breaker wird **nicht mehr verwendet, um nach mehreren Fehlern neue Sender direkt über Joyn zu starten**. Das widersprach dem Grundsatz „SAT bevorzugt“.

## Schneller Joyn-Fallback durch Prewarm

Die bisherige erste Umschaltung war langsam, weil erst beim Fehler der gesamte Joyn-Pfad aufgebaut wurde. Das ist geändert.

Sobald für den aktuell laufenden SAT-Sender ein Joyn-Mapping bekannt ist, bereitet I Launcher im Hintergrund bereits das konkrete Joyn-Playback vor, während SAT normal weiterläuft:

- stabile Joyn-Channel-ID;
- Länderroute;
- Entitlement/Playlist;
- DASH-Manifest und DRM-Daten;
- zugehöriger Loopback-Bridge-Pfad.

Diese Vorbereitung wird für die aktuelle Sender-Session gehalten. Bei der tatsächlichen manuellen oder automatischen Umschaltung kann der Player deshalb die vorbereitete Route wiederverwenden. Beim Zappen wird die Vorbereitung der alten Sender-Session verworfen und für den neuen SAT-Sender neu aufgebaut.

## Player

Joyn läuft weiterhin im **gleichen I-Launcher-Media3-Player**. Senderliste und EPG ändern sich beim Quellenwechsel nicht.

Beispiele für die Statusanzeige:

```text
Quelle · SAT · Joyn CH bereit · Auto
```

```text
Quelle · SAT · Joyn CH bereit · SAT manuell
```

```text
Quelle · Joyn CH · manuell
```

oder bei automatischer Übernahme:

```text
Quelle · Joyn CH · Fallback
Satellit gestört · <Grund>
```

## Wichtige Dateien

- `app/src/main/java/com/andreassamitsch/ilauncher/ui/livetv/LiveTvPlayerScreen.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/data/joyn/JoynLiveTvFallbackRepository.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/data/livetv/LiveTvJoynFallbackStore.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/data/livetv/LiveTvReceptionMonitor.kt`
- `app/src/main/java/com/andreassamitsch/ilauncher/data/joyn/JoynPlaybackBridgeClient.kt`
- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynPlaybackBridgeService.kt`

## Build- und Bestätigungsstatus

Die neue SAT-first-/Manual-/Prewarm-Logik ist ab Source-SHA **`7cc2b958fb4ce1e276e78f8a81dc23fa5f223a31`** enthalten. Der erste erfolgreiche und im I-Launcher-Updater veröffentlichte Build ist **`0.1.0-dev.524`**.

Bereits auf realer Hardware bestätigt sind SAT-Signaldiagnose, OSCam-Diagnose, Bouquet-Mapping, die grundsätzliche SAT→Joyn-Fallback-Auslösung sowie der Cross-App-Handoff zur Joyn-App. Noch zu testen sind die neue manuelle Rückkehr zu SAT, die persistente Auto-Umschaltung, die konservativeren Schwellen, die CH-Qualitätspräferenz und die tatsächliche Zeitersparnis durch das Prewarm.

Für AT ist Joyn TV mindestens `.641` erforderlich. Diese Version spielt AT direkt und erzwingt kein Mysterium-Gateway.
