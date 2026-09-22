# Joyn TV – aktueller technischer Stand

> **Für neue Chats / neue Entwicklungssitzungen:** Diese Datei zuerst lesen, wenn es um `joyntv`, Mysterium, Geo-Routing, Joyn-Live-TV oder die Joyn-Integration in I Launcher geht. Das Repo ist die maßgebliche Quelle; ältere Chat-Annahmen dürfen diesen Stand nicht überschreiben.

Stand: **2026-09-22**  
Entwicklungs-Branch: **`feature/joyn-tv-client`**  
PR: **#46 – `feat: add standalone Joyn Android TV client`**

## 1. Auf realer Android-TV-Hardware bestätigter Joyn-Aufbau

Die eigenständige Joyn-TV-App funktioniert auf dem realen TCL Android TV mit Mysterium als **app-lokalem Residential-HTTP-CONNECT-Proxy** für Märkte, für die Geo-Routing benötigt wird. Ein systemweiter Android-VPN-Tunnel ist für den bestätigten Standardpfad nicht nötig.

Damit gilt:

- nur der dafür konfigurierte Joyn-Verkehr wird über Mysterium geroutet;
- Android TV und andere Apps bleiben direkt verbunden;
- `JoynMysteriumProxyBridge` stellt lokal einen Loopback-CONNECT-Proxy bereit und injiziert die kurzlebigen Mysterium-Credentials zum Upstream;
- Joyn-HTTPS, DASH, DRM und CDN-Verkehr bleiben innerhalb des CONNECT-Tunnels Ende-zu-Ende verschlüsselt.

Historischer Hardware-Meilenstein: Joyn TV `0.1.0-dev.563` war der erste vom Benutzer explizit mit **„funktioniert jetzt“** bestätigte Build nach dem entscheidenden Proxy/TLS-Fix. Spätere Versionen ersetzen `.563`; die Nummer ist nur der Verifikationspunkt.

## 2. Mysterium-Login

Der OAuth-Token-Austausch muss wie in der offiziellen Mysterium-App erfolgen:

- `/oauth/token` mit **`application/x-www-form-urlencoded`**, nicht JSON;
- u. a. `grant_type`, `client_id`, Gerätekennung, `code_verifier`/`code` bzw. Refresh-Token;
- Geräteinformationen werden mitgesendet;
- Netzwerk-, TLS-, DNS- und Timeoutfehler dürfen nicht verschluckt werden.

Dieser Login-/Tokenweg ist auf dem realen TV bestätigt.

## 3. Mysterium Residential: entscheidender TLS-443-Sonderfall

Die Mysterium-Connect-Proxy-API lieferte beim realen TV-Test unter anderem `supervpn-dc-eu-01.mysterium.network:8080`. Auf dem TCL war `:8080` nicht erreichbar, während `:443` zunächst am Trust-Anchor scheiterte. Die funktionierende Implementierung in `JoynMysteriumProxyBridge` probiert für die bekannten EU-Superproxy-Hosts zuerst TLS auf 443 und erlaubt den eingegrenzten Zertifikats-Fallback ausschließlich für die passende Domain und diesen Port unter fortbestehender Prüfung von Gültigkeit, Kette und Hostname. **Die TLS-Prüfung niemals global abschalten.**

## 4. Residential-Lease- und Routing-Regeln

- Einen Lease über mehrere CONNECTs wiederverwenden.
- Cloudflare-/Trace-IP ist nur Diagnose; die tatsächliche Joyn-Freigabe ist autoritativ.
- Nicht für jeden fehlgeschlagenen Versuch sofort neue Credentials anfordern.
- Lease erst nach wiederholten echten Route-/Transportproblemen rotieren.
- Player-/Country-Route während eines laufenden Playbacks stabil halten.

## 5. Player-Qualität – bestätigt

Die Joyn-TV-App liest verfügbare Qualitätsstufen dynamisch aus den tatsächlichen Media3/DASH-Tracks. Am 13.09.2026 wurde bei Sendern der ProSiebenSat.1-Gruppe Schweiz in unserer Joyn-App 1080p als angeboten bestätigt. Die AT-Folgenqualität darf daraus nicht abgeleitet werden.

## 6. I Launcher SAT-/OSCam-Diagnose – bestätigt

Der Gigablue-SAT-Pfad ist die bevorzugte Quelle. I Launcher zeigt RF-/Tunerwerte und OSCam-ECM-Status an; RF-Werte belegen nicht automatisch eine funktionierende Entschlüsselung. Für die Ziel-Gigablue ist nur ein Tuner aktiv. OSCam-Zuordnung über die Enigma2-SID wurde am 16.09.2026 bestätigt.

## 7. I Launcher SAT → Joyn-Fallback

Nur die Gigablue-Bouquetliste ist sichtbar; SAT ist primär, Joyn Ersatz. Der normalisierte Senderfamilien-Matcher bevorzugt bei exakter Zuordnung Joyn **CH → AT → DE**, unabhängig von regionalen Namenszusätzen wie „Schweiz“ oder „Austria“. Das Inventar läuft nach zehn Minuten ab und nicht-CH-Zuordnungen werden erneut geprüft. Explizite Aliase für das `TV Sat`-Bouquet (71 Einträge) umfassen unter anderem NITRO ↔ RTL NITRO, ATV2 ↔ ATV II, Pro7 MAXX ↔ ProSieben MAXX und BR Süd ↔ BR Fernsehen Süd. Nicht unscharf matchen. AT wird direkt gespielt, CH/DE über den app-lokalen Mysterium-Residential-Pfad; I Launcher darf keinen globalen Proxy installieren.

## 8. I Launcher Live-TV-Bedienung und EPG

OK öffnet die Live-TV-Senderreihe, „Info“ den großen Sendungs-Hero, „EPG“ den Guide. Der große Info-Hero folgt dem Senderfokus, ohne allein dadurch den laufenden Stream zu wechseln; bei aktiviertem Hero bleibt die Senderreihe sichtbar, die Buttonleiste wird ausgeblendet. Der lokale Guide wird alle 15 Sekunden an den aktuellen Sendungsgrenzen neu zugeordnet, OpenWebif etwa minütlich, XMLTV regulär stündlich. Rechts oben vor den Einstellungen steht die lokale Uhrzeit.

## 9. Weiterschauen / Android TV Watch Next

Joyn TV speichert Resume-Position lokal und synchronisiert bei angemeldetem Konto über Joyns Resume-API. Beim Öffnen wird Resume-Asset-ID von Video-ID getrennt behandelt. Die App veröffentlicht unfertige Inhalte über Androids `WATCH_NEXT_TYPE_CONTINUE` und erkennt neue Episoden nach fertig angesehenen Folgen über regelmäßige Checks mit `WATCH_NEXT_TYPE_NEXT`. I Launcher liest Android-TvProvider-Watch-Next-Einträge in seiner eigenen Weiterschauen-Zeile.

Joyn TV übermittelt pro Episode Staffel/Folge, Folgenbild, Serien-/Staffelmotiv, korrektes Joyn-Logo und Kurzbeschreibung getrennt. Bei gleichnamigen Serien wie Bauer sucht Frau (AT/DE) darf die titelbasierte TMDB-Suche keine fremden Logos oder Bilder überschreiben. Ein verifizierter österreichischer TMDB-Episodentext darf als längere Beschreibung genutzt werden, wenn Serienidentität, Herkunftsland AT, Staffel und Folge eindeutig übereinstimmen; andernfalls bleibt der Joyn-Text. Der I-Launcher-Hero bevorzugt Episodenbilder.

### Joyn-API-Folgenbild in hoher Auflösung (22.09.2026)

Der Benutzer hat für die österreichische Bauer-sucht-Frau-Folge S23E3 dieselbe Joyn-Bild-ID in zwei Profilen gefunden:

- `.../i_p3htxmwhu58j_f8fb7582.jpg/profile:nextgen-web-livestill-503x283`
- `.../i_p3htxmwhu58j_f8fb7582.jpg/profile:nextgen-web-primarycut-1920x1080`

`JoynApiClient` entnimmt die konkrete Bild-ID weiterhin ausschließlich den API-Daten der **jeweiligen** Joyn-Episode. `JoynHighResEpisodeArtwork` leitet daraus (ausschließlich für `https://img.joyn.de/ingest/...` und mit unveränderter Bild-ID) die Full-HD-Primarycut-Rendition und die unprofilierte Original-URL ab. Der Player/Publisher prüft diese URLs **asynchron, mit kleinem begrenztem HTTP-Range-Abruf und echten Bitmap-Pixelabmessungen** und wählt für den Hero die größte nachweislich bessere Breitbildvariante. Eine nicht vorhandene, zu kleine oder hochformatige Variante wird nicht übernommen; es bleibt das API-Bild. Wiederholte Prüfungen werden pro URL zwischengespeichert bzw. nach Fehlschlag für sechs Stunden gedrosselt. Der neue v2-Cache lässt zuvor als Original gecachte Bilder erneut gegen Primarycut prüfen. Diese Logik wird sowohl beim normalen Weiterschauen als auch bei „Nächste Folge“ benutzt; nach erfolgreicher Prüfung wird der bestehende Android-TV-Watch-Next-Eintrag erneut publiziert und I Launcher kann den Hero aktualisieren.

**Die 1920×1080-Variante ist durch den Benutzer für S23E3 als URL beobachtet; die tatsächliche Bereitstellung für jede andere Episode sowie ein 4K-Original sind nicht zugesichert.** Keine hartcodierte Bild-ID und kein blindes Ersetzen des Größenprofils ohne HTTP-Bildprüfung.

## 10. Regeln für zukünftige Änderungen

- SAT/Gigablue bleibt bevorzugt; manuelles SAT/Auto-Fallback und CH/DE-Routing müssen erhalten bleiben.
- Mysterium-TLS-Fallback niemals globalisieren; Joyn AT direkt, CH/DE nur über die bewährte App-Route.
- Bei unbekannten Sendernamen keine unscharfen Joyn-Matches und keine Joyn-only-Sender in der Gigablue-Bouquetliste.
- Weiterschauen muss die exakte Episode identifizieren; Staffel/Folge, Artwork und AT-Branding dürfen nicht über ähnlich benannte deutsche Serien geraten werden.
- Netzwerk-/Fallback-/Bildänderungen immer auf realer TV-Hardware validieren.
