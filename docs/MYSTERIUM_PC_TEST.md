# Mysterium Residential / Joyn PC-Test

**Historischer Vorbereitungsbericht.** Die authentifizierten Live-Tests und
anschließenden Korrekturen sind inzwischen abgeschlossen und im
[Ergebnisbericht vom 2026-09-12](MYSTERIUM_PC_RESULTS_2026-09-12.md) dokumentiert.
Die folgenden Abschnitte „Tatsächlich gemessen“ und „Aus dem Android-Code“
beschreiben den ursprünglichen Vorabtest bzw. den Code vor den Korrekturen.
Insbesondere gilt „keine Credentials / keine echte Lease“ nicht mehr für den aktuellen Stand.

Stand: 2026-09-12, Branch `feature/joyn-tv-client`, analysierter Commit
`1c4063967a5fd413dccb7524aca282df2a6ddad6`.

## Tatsächlich gemessen

Der frische Checkout und die Prozessumgebung enthielten keine Mysterium-Zugangsdaten.
`python tools/test_mysterium_proxy.py --preflight` führte echte HTTPS-Anfragen
an die im Android-Code verwendete Mysterium-API aus, ohne Authorization-Header.

| Anfrage | HTTP | Antwort / Ergebnis |
| --- | --- | --- |
| GET `/api/v1/auth/config` | 200 | JSON-Felder `google_client_id`, `apple_service_id` |
| POST `/api/v1/connection/connect-proxy`, DE | 401 | `error.code=unauthorized`, `error.message=Unauthorized` |
| POST `/api/v1/connection/connect-proxy`, CH | 401 | `error.code=unauthorized`, `error.message=Unauthorized` |

| Country | Exit IP | CONNECT | Joyn API | Entitlement | VPN detected |
| --- | --- | --- | --- | --- | --- |
| DE | nicht erhalten | nicht getestet | nicht getestet | nicht getestet | UNKNOWN |
| CH | nicht erhalten | nicht getestet | nicht getestet | nicht getestet | UNKNOWN |

Es gibt **noch keinen empirischen Nachweis**, dass Mysterium diese Proxy-Anforderung
authentifiziert akzeptiert oder Joyn einen solchen Exit freigibt. Ein HTTP 401 beweist
weder das erfolgreiche Request-Schema noch das Proxy-Protokoll. Keine erfundenen
Host-/Port-/Country-/Lease-Werte. Kein Android-, APK-, VPN- oder WireGuard-Test.

Die maskierten Originalantworten dieses Vorabtests befinden sich lokal in
`C:\Users\djsam\AppData\Local\Temp\mysterium-pc-mxv7ubn7`.
Temporäre Dateien können vom Betriebssystem entfernt werden.

## Ausführen

Python 3.10+ und `requests` werden benötigt; auf dem Test-PC waren Python 3.12.10
und requests 2.32.3 bereits verfügbar. Keine neue Android-Abhängigkeit.

Token lokal als `MYSTERIUM_ACCESS_TOKEN` setzen, alternativ eine lokale JSON-Datei
über `--config` angeben. Die Datei darf folgende Schlüssel enthalten:

- `MYSTERIUM_ACCESS_TOKEN`: erforderlicher Mysterium-Bearer-Token.
- `JOYN_API_KEY_DE`, `JOYN_API_KEY_CH`: optionale lokale Cache-Werte. Ohne diese
  extrahiert der Test `API_GW_API_KEY` aus dem Joyn-Web-Bundle über denselben Proxy.

Keine echten Zugangsdaten in Dokumentation, Shell-History, Chat oder Git schreiben.
`tools/mysterium.local.json` ist zusätzlich in `.gitignore` erfasst; bevorzugt
eine Datei außerhalb des Repositories verwenden.

```powershell
# Vollständiger Test: fünf Lease-Anforderungen pro Land, DE dann CH.
python tools/test_mysterium_proxy.py --config C:\private\mysterium.json

# Zunächst nur eine Lease-Anforderung je Land.
python tools/test_mysterium_proxy.py --config C:\private\mysterium.json --leases 1

# Bis zu zehn Lease-Anforderungen je Land; Duplikate werden ausgewiesen.
python tools/test_mysterium_proxy.py --config C:\private\mysterium.json --leases 10

# Ohne Credentials: öffentliche Config und je eine nicht authentifizierte Anfrage.
python tools/test_mysterium_proxy.py --preflight

# Lokale Fixtures, ohne externe Proxy-/Joyn-Anfragen.
python -m unittest discover -s tools -p test_mysterium_proxy_test.py -v
```

Ein abgelaufener Access-Token stoppt den authentifizierten Lauf. Das Skript versendet
keine Magic-Link-Mail und erneuert keine Account-Tokens. Einen gültigen Token lokal
bereitstellen und neu starten. `--preflight` verwendet auch bei vorhandenem Token
ausdrücklich keine Authentifizierung und zählt nicht als Residential-Test.

Jeder Lauf legt ein eigenes `mysterium-pc-*`-Verzeichnis im System-Temp an:
maskierte API-JSON-Antworten, CONNECT-Varianten, Einzelergebnisse, aggregiertes JSON
und `results.csv`. Kein Token/Passwort wird absichtlich im Klartext persistiert;
Usernames werden vollständig maskiert. Für HTML/JS bleiben Status, Fehlerindikatoren,
Bytezahl und SHA-256 statt potenziell geheimnishaltigem Quelltext erhalten.
Provider-, Country-, ID-, Expiry- und TTL-Felder werden mit ihrem tatsächlichen
JSON-Pfad ausgegeben, soweit vorhanden. `remaining_seconds_computed` ist die aus
einem ISO-Zeitstempel berechnete Restlaufzeit, kein behaupteter API-TTL-Wert.

Exit-Code 0 bedeutet: alle Ergebniszeilen sind `ACCEPTED`; 2 bedeutet, dass dies
nicht bestätigt ist, einschließlich fehlender Credentials und Vorabtest.
Fünf Anforderungen garantieren keine fünf unterschiedlichen IPs. Das Skript führt
keine unbeschränkte Rotation durch, beendet sich bei Auth-/Abo-/Rate-Limit-Fehlern
und hält jede Lease während ihres Tests unverändert. Der nächste Test kann durch
`reset_connection=true` die vorherige Lease invalidieren; keine Paralleltests.

## Aus dem Android-Code ermittelter Vertrag

Quelle: `joyntv/src/main/java/com/andreassamitsch/joyntv/`.

### JoynMysteriumApiClient.kt

- Basis: `https://api.mysteriumvpn.com/api/v1`, direkter OkHttp-Client
  (`Proxy.NO_PROXY`), Connect 7 s, Read 15 s, Call 22 s.
- Status: GET `/auth/config`, autorisiert `/auth/check`, `/subscription`,
  `/connection/config/locations?ip_type=residential`.
- Anmeldung: POST `/magic-link`, PKCE S256 mit `client_id=app`;
  POST `/oauth/token` mit `authorization_code` oder `refresh_token`,
  Device `{os_type: android, id: UUID, title: Joyn TV}`.
- Access-/Refresh-Tokens lokal in SharedPreferences `joyn_protocol`.
  Manueller Access-Token wird unterstützt. Refresh bei Lease-HTTP 401 einmal.
- Header: `Authorization: Bearer …`, `Accept: application/json`, JSON-Content-Type,
  `x-client-version: joyntv-1`, `x-client-platform: android`,
  `User-Agent: JoynTV/AndroidTV Mysterium-Residential-Integration`.
- POST `/connection/connect-proxy` mit exakt:
  `{"country":"DE","ip_type":"residential","reset_connection":true,"os_type":"android"}`;
  CH identisch mit geändertem Country. Der PC-Test übernimmt auch `os_type=android`
  für einen vergleichbaren API-Aufruf, ohne Android auszuführen.
- Parser erwartet `proxy_config` oder `proxyConfig`, darin `host`, `port`,
  `username`, `password`, `expires_at` oder `expiresAt`. Port 1–65535.
  `limit_exceeded` / `limitExceeded` wird als Limit behandelt.
- Das Kotlin-Lease-Modell speichert **keine** Country-, Provider-, Node-, Lease-ID-
  oder TTL-Felder. Ob der Server diese liefert, ist ohne echte Lease unbekannt.

### JoynMysteriumSettings.kt / JoynProxySettings.kt

- Erfolgreiche Proxy-Profile pro Land speichern Host, Port, Username, Passwort,
  Quelle, Expiry, Verifikationszeit. Keine neue Lease pro HTTP-Anfrage.
- Refresh-Bedarf bei ISO-Expiry in höchstens 90 s; fehlt eine lesbare Expiry,
  gilt 20 Minuten nach Verifikation als Fallback-Frist. Das ist App-Logik,
  keine nachgewiesene Server-Lease-Laufzeit.
- Mysterium-Profile nutzen die lokale Bridge über einen stabilen ProxySelector;
  dessen Konfiguration wird für neue Verbindungen gelesen. Der Scanner erzwingt
  `allTraffic=true`. Bei Bridge-Startfehlern wird ein nicht erreichbarer
  Loopback-Proxy statt direktem Internet verwendet.
- Eine Bridge wird anhand Host, Port, Username und Passwort geteilt. Der lokale
  Client kennt keine Upstream-Credentials. Manuelle Proxies verwenden einen
  getrennten OkHttp-/Java-Authenticator.

### JoynMysteriumProxyBridge.kt vs. PC-Test

| Aspekt | Android | PC-Test |
| --- | --- | --- |
| Upstream | exakter Lease-Host/Port | exakter Lease-Host/Port |
| Transport | 443/8443 TLS zuerst, sonst HTTP zuerst; Fallback bei Protokoll-/Netzfehler | beide separat messen; erfolgreiche Variante in derselben Port-Reihenfolge auswählen |
| Ablehnung | echtes HTTP-Reject, z. B. 407, beendet Transport-Fallback | beide Ergebnisse bleiben sichtbar; die Diagnose kann daher mehr Varianten ausprobieren |
| CONNECT | Hostname:443 aus Client-Anfrage | explizit `api.joyn.de:443`; Zielrequests durch requests-CONNECT |
| Auth | Basic, Base64 von ISO-8859-1 `username:password`, pro CONNECT | Raw-Probe identisch; requests-Proxyauth mit URL-escaped Originaldaten |
| Proxy-TLS | Zertifikats-/Hostname-Prüfung | Standard-SSL-Kontext bzw. requests mit Zertifikatsprüfung |
| Ziel-TLS | Caller führt TLS nach CONNECT aus | requests führt verifiziertes Ziel-TLS nach CONNECT aus |
| DNS | Proxy-Host lokal; Zielhostname an Proxy | gleich, keine direkte Zielverbindung als Fallback |
| Timeout | Bridge-Header/Connect 8 s; Tunnel danach unbegrenzt | Raw-Probe 8 s Socket-Timeout; requests Connect 8 s / Read 20 s |
| Headergröße | 32 KiB | Raw-Probe 32 KiB |
| Keep-Alive | `Proxy-Connection: Keep-Alive`; relayed Zielverbindung | Raw-Probe gleicher Header; Zielrequests bewusst `Connection: close` zur Wiederverwendungsprüfung |
| Fehler | 502 nur vor erfolgreichem CONNECT; normale Tunnel-Abbrüche rotieren nicht | Phase/Exceptiontyp/Status; kein automatischer Lease-Wechsel innerhalb eines Tests |

Die Python-Timeouts sind Socket-/Read-Timeouts, kein OkHttp-Call-Deadline-Äquivalent.
Die diagnostische Transportauswahl ist absichtlich explizit und **kein Beweis**,
dass Android denselben Exit schon erfolgreich benutzen kann. Ein bestätigter
Transportfehler wird erst nach einem authentifizierten Live-Vergleich korrigiert.

### JoynMysteriumProxyScanner.kt / Joyn-Protokoll

Scanner: Lease anfordern, Cloudflare-Trace (IPv4) prüfen, Country vergleichen,
Lease-/Exit-Duplikate überspringen, Joyn-Web → Anonymous → GraphQL → Entitlement.
Der Scanner erstellt für Trace und Joyn getrennte Bridges mit denselben Credentials.
OkHttp: Connect 7 s, Read 12 s, Call 20 s. Die angezeigte Scanner-Latenz misst den
Joyn-Probe-Ablauf; die PC-Tabelle misst explizit TCP + ggf. Proxy-TLS + CONNECT.
Der PC-Test lässt auch IPv6 zu und dokumentiert diese Abweichung.

`JoynProtocol.kt`, `JoynModels.kt` und `JoynApiClient.kt` liefern:

- Bootstrap: `https://www.joyn.de/` bzw. `.ch/`, erste maximal 50 Script-Sources,
  Suche nach `API_GW_API_KEY ... value:"..."`. Scanner selbst benötigt bereits
  gecachten API-Key; PC-Test bootstrapped ihn über den getesteten Proxy.
- POST `https://auth.joyn.de/auth/anonymous`, JSON `anon_device_id`, `client_id`
  als UUIDs, `client_name=web`; Country-Header DE/CH und Auth-Tenant JOYN_DE/JOYN_CH.
- GET `https://api.joyn.de/graphql`, `x-api-key`, `Joyn-Platform=web`,
  Authorization aus Anonymous-Token, GraphQL-Tenant JOYN für DE bzw. JOYN_CH für CH.
- Exakt die Scanner-Abfrage `MysteriumLiveProbe` mit `liveStreams`, LINEAR,
  first 30, offset 0, `id markings`. Erster Sender ohne PLUS/PREMIUM-Markierung;
  fehlende Paid-Markierung garantiert noch keinen erfolgreichen Gratiszugang.
- POST `https://entitlements-service-alb.prd.platform.s.joyn.de/api/user/entitlement-token`,
  Authorization, JSON `content_id` und `content_type=LIVE`.
  Akzeptanz erfordert erfolgreichen HTTP-Status **und** `entitlement_token`.
- Alle Schritte, einschließlich Bootstrap-Scripts und Redirects, bleiben auf
  derselben Proxy-Session. Drei GraphQL-Anfragen und Trace vor/nach Joyn dienen
  als Wiederverwendungstest über mehrere TCP-Verbindungen, ohne neue Lease.

`ACCEPTED` erfordert außerdem stabile gemessene IP und passendes Trace-Land.
`VPN_DETECTED` erfordert einen expliziten VPN-Indikator. Geo-Block, Login/Premium,
unspezifisches Access denied, sonstige Entitlement-Fehler, wechselnde Exit-IP,
falsches Exit-Land und Proxy-/Trace-Netzfehler bleiben unterscheidbar.
Bei fehlendem Entitlement bedeutet `UNKNOWN` ausdrücklich nicht „VPN-frei“.
Alle beobachteten `ENT_*`-Codes und Marker bleiben in den maskierten Diagnosen,
auch wenn mehrere Fehlerkategorien gleichzeitig auftreten. Der Test misst keine
Videowiedergabe, DRM-Lizenz oder langfristige Stabilität über den Ablauf hinaus.

### JoynRepository.kt: vorhandene Architekturabweichung

`ensureMysteriumForCountry()` verwendet gültige Proxy-Profile bevorzugt und fordert
nicht pro Stream eine Lease an. Bei fehlendem/abgelaufenem Proxy-Profil wählt es
jedoch weiterhin einen vorhandenen WireGuard-Fallback oder lässt bei fehlendem
Fallback die direkte Route zu. Eine reine Proxy-Erneuerung ist in diesem Zweig
nicht implementiert. Außerdem vergleicht der schnelle Wiederverwendungszweig nur
Country, Host und Port, nicht geänderte Credentials desselben Gateways.

Das sind statisch sichtbare Unterschiede zum gewünschten ausschließlich proxybasierten
Betrieb. Ohne funktionierende authentifizierte PC-Referenz wurde weder ein
Protokollfehler behauptet noch der produktive Routing-Code spekulativ umgebaut.

## Lokale Validierung

Sieben Python-Tests bestanden: Parsing/Limitprüfung, Secret-Maskierung, echte lokale
TCP-CONNECT-Fixture mit Auth bei drei Verbindungen und 407-Abgrenzung,
URL-Encoding, Joyn-Fixture mit drei GraphQL-Anfragen und korrektem CH-Tenant,
VPN-vs.-Forbidden-Klassifikation, kein Direkt-Fallback bei Proxy-Verbindungsfehler.
`py_compile` erfolgreich. Diese Fixtures ersetzen keine reale Residential-Lease.
