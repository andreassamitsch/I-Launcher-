# ServusTV: Aktualisierung und Katalog messen

## Ablauf

**Aktuelles** nutzt zuerst die verifizierten ServusTV-Sendungsprodukte und deren dynamisch gelieferte redaktionelle Collections. Nur wenn die benötigten Formate nicht direkt erreichbar sind, greift die bisherige vollständige Textsuch-/Collection-Discovery als Fallback. Dabei werden bis zu acht neue direkte Videotreffer mit höchstens zwei gleichzeitigen Produktanfragen bereits während der Collection-Suche vorgeladen und später nicht doppelt geladen. Logcat meldet `Aktuelles source=direct` oder `Aktuelles source=search-fallback` sowie `Aktuelles refresh` mit `discovery`, `remainingDetails` und `firstCache` (Millisekunden).

**Sendungskatalog**: Das Produkt `sendungen` liefert die Kategorien und Sendungen; der reguläre Metadatenabgleich erfolgt höchstens alle sechs Stunden oder auf ausdrückliche Anforderung. Höchstens vier Kategorien einschließlich ihrer Folgeseiten werden gleichzeitig angefragt. Sobald die gesamte Katalog-Metadatenliste bereitsteht, wird sie sofort im lokalen Cache gespeichert, noch bevor die ausgewählten Sendungen/Collections zusätzlich aktualisiert und Android-TV-Kanäle veröffentlicht werden. Dadurch kann die Oberfläche den Katalog bereits anzeigen, während die nachgelagerte Arbeit noch läuft. `Katalog cache: categories=…, elapsed=…ms` meldet den Speicherzeitpunkt seit Beginn des gemeinsamen Refreshs; dies ist **kein** gemessener Zeitpunkt des ersten sichtbaren Frames.

**Sendungsdetail**: Bereits gespeicherte Folgen werden beim Öffnen sofort angezeigt. Das Produkt der geöffneten Sendung und seine Folgen-Collections werden anschließend direkt abgefragt; weitere Seiten werden bei Bedarf nachgeladen. Empfehlungen und andere nicht redaktionelle Collections werden nicht als Folgen behandelt.

## Reproduzierbarer Gerätetest

1. Vorhandene Version `0.2.0-dev.67` auf dem Android-TV mit warmem Cache öffnen. Verfügbarkeit und Zeitpunkt der ersten Aktuelles-Karte, der Kategorien und der Folgen beim Öffnen einer Sendung beobachten.
2. Neuere stabil signierte ServusTV-Entwicklungs-APK über den In-App-Updater installieren. Unter vergleichbaren Netzwerkbedingungen erneut prüfen; soweit möglich auch einen Katalogabgleich nach sechs Stunden oder bei einem frischen Cache erfassen.
3. Diagnose parallel aufzeichnen: `adb logcat -s ServusRepository:I`. `Aktuelles refresh` und `Katalog cache` zwischen den Versionen vergleichen, ohne Log-Zeitwerte mit Bildaufbauzeiten gleichzusetzen.
4. Die Kategorien und Sendungsanzahl müssen vollständig bleiben; bestehende Folgen, redaktionelle Bereiche, ServusTV-Live-Kanäle, Nachrichtenversionen und Logos prüfen. Bei laufendem Refresh D-Pad-Fokus und Scrollposition kontrollieren; es dürfen keine Fokusverluste oder unnötig wiederholte Neuzeichnungen auftreten.
5. Direktaufruf-Fehler müssen weiterhin den Such-Fallback zulassen. Die tatsächliche Performanceverbesserung erst nach Gerätevergleich behaupten; ein CI-Build ist kein Hardwaretest.

## Verifizierte direkte Quellen (20.09.2026)

Der öffentliche `/de/sendungen`-Einstieg entspricht dem API-Produkt `sendungen`. Das Nachrichtenprodukt `AA-1Y5RJCD1H2111` enthält die redaktionellen Collections „Servus Nachrichten in 90 Sekunden“, „Servus Nachrichten 19:20“ und „Einzelbeiträge“. Für die schnelle Aktuelles-Reihe werden nur die ersten beiden verwendet; Einzelbeiträge bleiben auf der Sendungsdetailseite. Das eigene Produkt `AA-1Q66UK71N1W11` liefert „Der Wegscheider“ mit der Collection „Aktuelle Sendungen“. Empfehlungen und „Mehr zu“ sind keine eigenen Sendungsfolgen. Collection-IDs werden aus den API-Produktantworten gelesen und nicht fest verdrahtet.

Die Diagnoselogzeilen enthalten keine Account-Tokens oder vollständigen privaten Netzwerk-URLs.
