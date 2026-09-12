# Mysterium Residential / Joyn: authentifizierte PC-Messung

2026-09-12, Branch `feature/joyn-tv-client`. Python 3.12.10 / requests 2.32.3,
Windows-PC, zusätzlich Produktions-Kotlin-Bridge auf Java 17.
Keine Android-Laufzeit, kein Emulator, kein adb, keine APK-Installation oder Wiedergabe.

## Ergebnis

**Die API liefert verwendbare Credentials, aber auf diesem PC keinen erreichbaren
Proxy auf dem gelieferten Port 8080. Dieselben Credentials funktionieren auf
demselben Gateway mit verifiziertem TLS auf Port 443 und HTTP CONNECT.**

Joyn akzeptiert einige Verbindungen und erkennt andere ausdrücklich als VPN.
Die wichtigste Einschränkung: Dieselbe Lease liefert über neue Verbindungen
wechselnde Exit-IPs. Eine positive Freigabe lässt sich daher keiner zuvor bei
Cloudflare/ipify gemessenen IP zuverlässig zuordnen.

| Hauptserie | DE | CH |
| --- | ---: | ---: |
| Lease-Anforderungen / API HTTP 200 | 5 / 5 | 5 / 5 |
| Authentifizierte CONNECTs auf TLS:443 erfolgreich | 5 | 5 |
| Anonymous + drei GraphQL-Requests erfolgreich | 5 | 5 |
| Entitlement-Token erhalten | 3 | 2 |
| `ENT_USER_VPN_DETECTED` | 1 | 3 |
| `ENT_AssetNotAvailableInCountry` | 1 | 0 |
| Unterschiedliche IPs in Trace/ipify-Samples | 15 | 2 |
| Abläufe mit stabiler IP über alle Samples | 0 | 0 |
| Mittlere TLS-CONNECT-Latenz | 362,8 ms | 218,6 ms |

Mittelwert aller zehn TLS-CONNECTs: **290,7 ms**. Gemessen werden DNS/TCP,
TLS zum Proxy und CONNECT bis HTTP 200, nicht Bootstrap/Entitlement oder
die vorherigen fehlgeschlagenen Port-8080-Versuche.

Es wurden insgesamt 13 Lease-Antworten pro Land angefordert: 6 pro Land für
die Prüfung des exakten API-Ports, 1 für die erste TLS:443-Diagnose, 5 für die
Hauptserie und 1 für die JVM-Bridge-Prüfung. Je 7 Abläufe pro Land erreichten
Joyn. Über diese 7 Abläufe: DE 5 erfolgreiche Entitlements, 1 VPN-Erkennung,
1 Länderfehler; CH 2 erfolgreiche Entitlements und 5 VPN-Erkennungen.
Diese Zusatzmessungen werden nicht in die Hauptserien-Latenz eingerechnet.
Es wurden keine 13 unabhängigen/stabilen Exits pro Land nachgewiesen.

## 1. Exakte API-Anfrage

Anmeldung über den vorhandenen Magic-Link-/PKCE-Ablauf aus
`JoynMysteriumApiClient.kt`; der zweite neue Link wurde erfolgreich mit HTTP 200
gegen `/oauth/token` eingelöst. Verifier und Tokens ausschließlich lokal außerhalb
des Repositories; keine verwendbaren Secrets in den Ergebnisdateien.

```http
POST https://api.mysteriumvpn.com/api/v1/connection/connect-proxy
Authorization: Bearer <lokaler Access-Token>
Accept: application/json
Content-Type: application/json
x-client-version: joyntv-1
x-client-platform: android
User-Agent: JoynTV/AndroidTV Mysterium-Residential-Integration

{"country":"DE","ip_type":"residential","reset_connection":true,"os_type":"android"}
```

Für CH ausschließlich `country` zu `CH` geändert. Innerhalb eines einzelnen
Trace-/Joyn-/Wiederverwendungstests keine neue Lease angefordert.

## 2. Tatsächlich gelieferte Response

Beispiel DE, HTTP 200; ausschließlich Username und Passwort maskiert:

```json
{
  "proxy_config": {
    "host": "supervpn-dc-eu-02.mysterium.network",
    "port": "8080",
    "username": "***",
    "password": "***",
    "expires_at": "2026-09-13T05:40:11.630549304Z"
  },
  "limit_exceeded": false
}
```

Beobachtet wurden `supervpn-dc-eu-01`, `-02`, `-03`, jeweils unter
`.mysterium.network`. Host ist das Gateway, **nicht** die öffentliche Exit-IP.
Port kommt als JSON-String. Username und Passwort sind vorhanden und wurden
unverändert zur Authentifizierung verwendet. **Keine** zusätzlichen Country-,
Provider-, Residential-Node-, Lease-ID- oder TTL-Felder kamen zurück.
Die angegebene Ablaufzeit liegt rund 24 Stunden nach der Anforderung
(berechnete Restlaufzeit beim Parsen meist 86.399 Sekunden).
`ip_type=residential` wurde angefordert; eine unabhängige Klassifikation aller
Exits als Privatkundenanschlüsse ist damit nicht bewiesen.

## 3. Transport und CONNECT

| Variante | Beobachtung |
| --- | --- |
| API-Host:8080, normales HTTP | TCP verweigert, Windows 10061; kein CONNECT-Status |
| API-Host:8080, TLS | derselbe TCP-Fehler vor TLS |
| derselbe Host:443, verifiziertes TLS, ohne Auth | HTTP 407 |
| derselbe Host:443, verifiziertes TLS, originale Lease-Credentials | HTTP 200 auf `CONNECT api.joyn.de:443` |
| Ziel-HTTPS innerhalb CONNECT | Cloudflare, ipify und Joyn erfolgreich erreichbar |

curl bestätigte unabhängig für Gateways `-02` und `-03` den Fehler auf 8080:
Exit 7, HTTP 000, CONNECT 000. System-DNS und Cloudflare-DNS-over-HTTPS lieferten
dieselben Gateway-Adressen. TCP:443 war erreichbar. Damit ist weder eine
Base64-Korrektur noch ein anderes Ziel-DNS die Lösung des 8080-Problems.
Ob das Zurückweisen von 8080 am Server oder auf dem Netzwerkweg entsteht,
ist durch diese Messung allein nicht abschließend bewiesen.

Funktionierender Aufbau:

```text
PC/Android-Client
  → TCP zum gelieferten Mysterium-Gateway:443
  → TLS mit SNI, Zertifikats- und Hostnamenprüfung
  → CONNECT api.joyn.de:443 HTTP/1.1
    Host: api.joyn.de:443
    Proxy-Authorization: Basic Base64(ISO-8859-1(username + ":" + password))
    Proxy-Connection: Keep-Alive
  ← HTTP/1.1 200
  → verifiziertes Ziel-TLS durch den Tunnel
  → Joyn-HTTPS
```

Authentifizierung erneut bei jedem neuen CONNECT. Ziel-DNS wird dem Proxy
überlassen; lokal wird der Gateway aufgelöst. Der Python-Test ignoriert
Umgebungs-Proxies/NO_PROXY/netrc und hat keinen direkten Ziel-Fallback.
Ein lokaler Regressionstest bestätigt dieses Verhalten bei Proxyfehlern.

## 4. Joyn-Hauptserie

Alle Abläufe: Web und API-Key-Bootstrap, neuer Anonymous-Token, drei GETs der
Scanner-GraphQL-Abfrage, freier LINEAR-Kanal, POST Entitlement, erneuter Exit-Trace.
DE-Kanal `daserste-de-hd`, CH-Kanal `srf1-ch-hd`.
Die tatsächlichen Joyn-Endpunkte/Headers stehen im
[Protokollvergleich](MYSTERIUM_PC_TEST.md).

**Die erste IP ist ein Trace-Sample, nicht die nachgewiesene Quell-IP des
Entitlement-Requests.** Sämtliche Zeilen änderten im weiteren Ablauf ihre IP.

| Land / Lease | Erste Trace-IP | TLS CONNECT | Token / GraphQL | Entitlement | VPN erkannt |
| --- | --- | ---: | --- | --- | --- |
| DE 1 | 217.154.118.12 | 938 ms | OK / OK | Länderfehler | UNKNOWN |
| DE 2 | 159.195.37.213 | 219 ms | OK / OK | OK | NO |
| DE 3 | 77.90.50.123 | 203 ms | OK / OK | HTTP 400 | YES |
| DE 4 | 45.84.196.4 | 266 ms | OK / OK | OK | NO |
| DE 5 | 87.106.247.144 | 188 ms | OK / OK | OK | NO |
| CH 1 | 83.228.241.94 | 203 ms | OK / OK | HTTP 400 | YES |
| CH 2 | 83.228.195.93 | 203 ms | OK / OK | OK | NO |
| CH 3 | 83.228.241.94 | 234 ms | OK / OK | HTTP 400 | YES |
| CH 4 | 83.228.241.94 | 234 ms | OK / OK | HTTP 400 | YES |
| CH 5 | 83.228.195.93 | 219 ms | OK / OK | OK | NO |

`NO` bedeutet in diesen Zeilen: Entitlement-Token erhalten und kein VPN-Fehler
in diesem Ablauf; keine dauerhafte Aussage über die Lease oder den IP-Pool.
Cloudflare meldete bei den Vorher-/Nachher-Samples jeweils DE bzw. CH.
Trotzdem kann Joyn ein anderes Geo-Ergebnis liefern: DE 1 lieferte ausdrücklich
`ENT_AssetNotAvailableInCountry`, nicht VPN-Erkennung. Der entsprechende Code
wurde als eigener Geo-Fall in die Skriptklassifikation aufgenommen.

## 5. Wiederverwendung und Produktions-Bridge

Credentials derselben Lease funktionierten über mehrere neue TCP-/CONNECT-
Verbindungen. Die abschließenden Traces waren erfolgreich, ohne Lease-Erneuerung.
Langfristige Verwendbarkeit bis zur 24-Stunden-Ablaufzeit wurde nicht abgewartet.

Die echte `JoynMysteriumProxyBridge.kt` wurde mit einem kleinen JVM-only-Stand-in
für `JoynProxyConfig` kompiliert; die Bridge selbst ist die Produktionsdatei.
Python verwendete anschließend ausschließlich deren lokalen CONNECT-Port.

- DE: API liefert Gateway `-01:8080`; Bridge stellt TLS:443 her; Anonymous,
  drei GraphQL-Anfragen und Entitlement erfolgreich. Trace vorher
  `185.213.240.127`, nachher `51.89.116.189` (beide DE).
- CH: gleicher Ablauf, Entitlement `ENT_USER_VPN_DETECTED`; Vorher-/Nachher-Trace
  jeweils `83.228.195.93`. Dies zeigt auch: Gleiche Stichproben vor/nach einem
  Ablauf beweisen keine feste IP zwischen allen Requests.
- Drei zusätzliche Requests mit HTTP Keep-Alive zum selben Cloudflare-Ziel
  zeigten DE dreimal `217.160.51.237`, CH dreimal `83.228.241.94`.
  Das gilt für diese wiederverwendeten Zielverbindungen, nicht automatisch für
  getrennte CONNECTs zu Auth-, GraphQL-, Entitlement-, DRM- und CDN-Hosts.

**Leases sind wiederverwendbar; ein dauerhaft fester, marktübergreifend getesteter
Residential-Exit ist mit dem beobachteten Vertrag nicht nachgewiesen.**
Keine erfundenen Sticky-Username-Suffixe oder undokumentierten API-Parameter
wurden eingesetzt.

## 6. Gefundene Fehler und Korrekturen

1. **Bridge verwendet nur den gelieferten Port.** Original probierte HTTP und TLS
   auf 8080; beide scheitern vor CONNECT. Korrigiert: ausschließlich für
   `supervpn-dc-eu-<Zahl>.mysterium.network` mit API-Port 8080 bevorzugt sie
   verifiziertes TLS auf 443. Originalport bleibt als Transport-Fallback erhalten.
   Andere Hosts/Ports behalten die ursprüngliche Auswahl. Ein echtes HTTP-Reject
   wie 407 führt weiterhin nicht zu blindem Transport-Retry.
2. **Scanner setzt erfolgreiche Freigabe mit vorher gemessenem Exit gleich.**
   Korrigiert: Nach erfolgreichem Entitlement neuer Trace mit derselben Lease.
   Bei fehlendem Trace, gewechselter IP oder falschem Land kein Profil speichern;
   Diagnose `EXIT_NICHT_STABIL`. Das erkennt sichtbare Rotation, garantiert aber
   keine IP-Bindung zwischen unterschiedlichen Zielhosts.
3. **Diagnoseskript:** Statuswerte dürfen nicht als Token-Secrets maskiert werden;
   Fehlernamen in erfolgreichen JS-Bundles sind keine tatsächlichen Serverfehler.
   Beide Fälle sowie der beobachtete Geo-Code wurden mit Regressionstests abgesichert.

Für belastbares Speichern/Rotieren einzelner „Joyn-akzeptierter IPs“ braucht die
App weiterhin einen **nachgewiesenen Vertrag für eine feste Exit-Zuordnung über
alle CONNECTs**. Der hier gemessene Response bietet dafür keine Node-/Session-
oder Exit-ID. Nur längeres HTTP Keep-Alive behebt das Problem über verschiedene
Joyn-Hosts nicht. Die bestehenden WireGuard-/Direkt-Fallbacks bei fehlenden bzw.
abgelaufenen Profilen wurden nicht umgebaut und im Test nie benutzt.

## 7. Wiederholen und Artefakte

```powershell
# Exakter API-Port zuerst; danach ausdrücklich TLS:443 auf demselben Host.
python tools/test_mysterium_proxy.py --config C:\private\mysterium.json --leases 5 --fallback-tls-port 443

# Ohne Zusatzdiagnose ausschließlich den API-Port testen.
python tools/test_mysterium_proxy.py --config C:\private\mysterium.json --leases 1

python -m unittest discover -s tools -p test_mysterium_proxy_test.py -v

# Lokale Kotlin-Compiler-Jars + Java 17; kein Android-SDK nötig.
python tools/mysterium-proxy-jvm/run_pc_tests.py

# runtime.json stammt aus dem vorherigen Befehl.
python tools/test_mysterium_bridge_pc.py --config C:\private\mysterium.json --runtime-json C:\Temp\mysterium-jvm-...\runtime.json
```

- [Hauptserie JSON](diagnostics/mysterium-pc-2026-09-12.json)
- [Hauptserie CSV](diagnostics/mysterium-pc-2026-09-12.csv)
- [Produktions-Bridge live](diagnostics/mysterium-bridge-2026-09-12.json)

Maskierte Einzelantworten lokal unter System-Temp:
`mysterium-pc-03tjotzs` (erste API-Port-Prüfung), `mysterium-pc-sjhe2zy5`
(5+5 API-Port-Prüfungen), `mysterium-pc-zy_eejjt` (erste TLS-Diagnose),
`mysterium-pc-dye__4_o` (Hauptserie), `mysterium-bridge-live-v6vwi982`
(Produktions-Bridge). Die JSON-/CSV-Artefakte enthalten keine verwendbaren Credentials.

Validierung: neun Python-Tests bestanden; Produktions-Bridge auf JVM kompiliert,
lokale CONNECT-/Auth-/Relay-/407-Fixture bestanden und DE/CH live durch die
Produktionsklasse geprüft. Zusätzlich `:joyntv:compileDebugKotlin` erfolgreich
(Gradle 9.6.1, 7 Tasks; nur bereits vorhandene Warnungen außerhalb der geänderten
Proxy-Dateien). Offline fehlte zunächst das Projekt-Plugin; die reguläre
Dependency-Auflösung und der anschließende Compile waren erfolgreich.
Kein APK-Build/-Test und kein Video-/DRM-Test.
