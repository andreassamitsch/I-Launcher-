# ServusTV: Aktuelles-Refresh messen

Die Aktuelles-Discovery bleibt vollständig: Suchbegriffe, redaktionelle Sendungs-/Collection-Zuordnung und abschließende Kandidatenplanung werden weiterhin durchlaufen. Bis zu acht neue direkte Videotreffer werden bereits **während** der Collection-Discovery mit höchstens zwei gleichzeitig laufenden Produktanfragen vorab geladen. Bereits erfolgreich vorgeladene Treffer werden nicht noch einmal in der abschließenden Detailphase angefragt. Seiten-/Show-Produkte werden dabei nicht vorab geladen, damit sie sich nicht mit der Collection-Ermittlung überschneiden. Die korrekte redaktionelle Identität des endgültigen Kandidatenplans hat Vorrang vor vorläufigen Hinweisen.

## Reproduzierbarer Gerätetest

1. Bestehende ServusTV-Version `0.2.0-dev.65` auf dem Android-TV starten, einmal die Nachrichten aktualisieren und Ladezeit bis zur ersten aktualisierten Aktuelles-Karte beobachten.
2. Stabile neue Entwicklungs-APK installieren, denselben Ablauf mit unveränderten Empfangs- und Netzwerkbedingungen wiederholen; einmal mit warmem Cache und, soweit möglich, einmal mit tatsächlich neuen Inhalten.
3. Während des Tests Logcat auf dem verbundenen Android-TV erfassen: `adb logcat -s ServusRepository:I`.
4. Die Logzeilen `Aktuelles discovery` (`search`, `collections+prefetch`, Kandidaten und vorgeladene Treffer) und `Aktuelles refresh` (`discovery`, `remainingDetails`, `firstCache`) vergleichen. Alle Zeitwerte sind Millisekunden; `firstCache` endet beim Speichern der Nachrichten im lokalen Cache und ist nicht automatisch mit dem ersten sichtbaren Karten-Frame gleichzusetzen.
5. D-Pad-Fokus, News-Format/Logo, Anzahl der Nachrichten, Live-TV, Kategorien und den vorhandenen Preview-Channel unverändert überprüfen. Eine messbare Verbesserung darf erst nach dem echten Gerätevergleich behauptet werden.

Die Logzeilen enthalten keine Account-Tokens oder vollständigen privaten Netzwerk-URLs.

## Verifizierte direkte Quellen (20.09.2026)

Der öffentliche `/de/sendungen`-Einstieg entspricht dem API-Produkt `sendungen`; dessen Katalog wird separat alle sechs Stunden geladen. Für den häufigen Aktuelles-Refresh werden die zwei verifizierten Show-IDs direkt abgefragt: `AA-1Y5RJCD1H2111` (Servus Nachrichten) und `AA-1Q66UK71N1W11` (Der Wegscheider). Deren API-Produktantworten liefern die Collection-IDs dynamisch. Im Nachrichten-Produkt sind die redaktionellen Collections „Servus Nachrichten in 90 Sekunden“ und „Servus Nachrichten 19:20“ die Quellen für die schnelle Aktuelles-Reihe; „Einzelbeiträge“ bleibt Bestandteil der Sendungsdetailansicht und wird nicht fälschlich als vollständige Nachrichtensendung einsortiert. Für den Wegscheider wird nur „Aktuelle Sendungen“ verwendet. „Das könnte Ihnen auch gefallen“ und „Mehr zu“ sind keine eigenen Sendungsfolgen.

Sind eine der erforderlichen Collection-Gruppen oder eine Show-API nicht erreichbar, bleibt die ursprüngliche vollständige Such-/Collection-Discovery als Fallback erhalten. Logcat `Aktuelles source=direct` bzw. `Aktuelles source=search-fallback` hilft beim Vergleich. Die bisherige begrenzte Vorab-Ladung greift weiterhin im Fallback. Die 20.09.2026 live überprüften Collection-IDs sind bewusst nicht fest verdrahtet, sondern werden aus dem Show-Produkt gelesen.
