# PULS 4 Hybrid-Qualitätstest

Stand: 2026-09-17

Status: **experimentell / noch nicht auf realer Hardware ausgewertet**.

## Ziel

Prüfen, ob der österreichische PULS-4-Livestream von Joyn durch eine gezielte Kombination aus österreichischer Joyn-Identität und Schweizer Residential-Netzweg ein anderes bzw. höherwertiges DASH-Manifest liefert.

Der Test ist ausschließlich Diagnose. Er verändert **keine** normale Senderpräferenz und PULS 4 bleibt im regulären Betrieb `Joyn AT`.

## Bedienung

In der Joyn-TV-App gibt es den eigenen Tab **Tests**. Dort wird der Test erst nach Auswahl von **PULS 4 Hybrid-Test starten** ausgeführt. Das bloße Öffnen des Tabs erzeugt keine Test-Netzwerkrequests.

## Testmatrix

1. **AT / AT – Referenz**
   - PULS-4-Content-ID aus Joyn AT
   - AT-Testtoken
   - Entitlement, Playlist und DASH-MPD direkt

2. **AT-Identität / CH-Netz**
   - gleiche AT-PULS-4-Content-ID
   - gleicher AT-Testtoken
   - Entitlement, Playlist und DASH-MPD über den gespeicherten CH-Residential-Lease

3. **CH-Identität / CH-Netz / AT-Content**
   - AT-PULS-4-Content-ID
   - separater CH-Testtoken
   - Entitlement, Playlist und DASH-MPD über CH

4. **AT-Entitlement / CH-Playback**
   - Entitlement direkt aus AT
   - Playlist und DASH-MPD anschließend über CH

Für jede Variante werden Manifest-Host und die im MPD angebotenen Video-Repräsentationen ausgewertet: höchste Auflösung, maximale Bitrate, maximale Framerate und Anzahl der Video-Repräsentationen.

## Isolation vom normalen Betrieb

Implementierung:

- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynPuls4HybridTester.kt`
- `joyntv/src/main/java/com/andreassamitsch/joyntv/JoynTestsPanel.kt`
- Einbindung des Tabs in `JoynHomeScreen.kt`

Der Tester verwendet **nicht** `JoynRepository.ensureMysteriumForCountry()` und schreibt weder Region noch Proxy-Konfiguration um.

Stattdessen werden pro Testlauf eigene temporäre OkHttp-Clients erzeugt:

- AT: explizit `Proxy.NO_PROXY`
- CH: eigener temporärer `JoynMysteriumProxyBridge`, gespeist aus dem bereits verifizierten gespeicherten CH-Residential-Lease

Nicht verändert werden:

- `JoynRegionSettings`
- `JoynProxySettings`
- aktiver `ProxySelector`
- gespeicherte Joyn-Kontosessions
- Sender-/Home-Cache
- Favoriten
- I-Launcher-Fallback-Mapping
- Player-Route-Pin

Der Test startet keinen Media3-Player, fordert keine Widevine-Lizenz an und lädt keine Videosegmente. Er endet nach dem Download und der Analyse des DASH-MPD.

## Voraussetzung

Es muss ein noch gültiger, zuvor von der normalen Joyn-/Mysterium-Logik erfolgreich getesteter CH-Residential-Lease vorhanden sein. Fehlt dieser oder gilt er als abgelaufen, startet der Test nicht.

## Noch offen

Die vier Ergebnisse müssen auf der realen Zielhardware verglichen werden. Erst danach darf aus diesem Experiment eine reguläre PULS-4-Routingregel abgeleitet werden.
