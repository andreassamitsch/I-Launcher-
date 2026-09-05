# ServusTV-Katalogdiagnose

Der `servustv-provider` behandelt `Aktuelles`, Live TV und den vollständigen Sendungskatalog bewusst getrennt.

Für den experimentellen Sendungskatalog wird auf realer Hardware eine kompakte Diagnose gespeichert und bei leerem Katalog direkt in der App angezeigt. Sie enthält nur strukturelle Informationen, insbesondere:

- Anzahl der Collections auf der `sendungen`-Landingpage
- Verteilung der gelieferten `list_type`-Werte
- Anzahl der Collections nach dem aktuellen Kategorie-Filter
- je Kategorie: Kartenanzahl und Anzahl der nach der aktuellen Policy erkannten Sendungskarten
- Anzahl eindeutiger Sendungskarten und das resultierende Kategorie-/Sendungsvolumen
- bei Fehlern die Stufe und den Exception-Typ

Vollständige URLs, Sessiondaten, Playback-Tokens und Content-IDs werden nicht in diese Diagnose übernommen.

Ein expliziter manueller Voll-Refresh (`Jetzt aktualisieren`) darf einen Katalogfehler nicht als Gesamterfolg darstellen. `Aktuelles` und Live TV dürfen zuvor erfolgreich aktualisiert und im Cache erhalten bleiben; der manuell angeforderte Katalogteil wird dennoch als fehlgeschlagen angezeigt. Automatische Hintergrundrefreshes dürfen dagegen bei einem Katalogfehler auf den letzten erfolgreichen Katalogcache zurückfallen.

Die Diagnose dient ausschließlich dazu, die reale ServusTV-API-Struktur zu verifizieren. Katalogfilter werden erst angepasst, nachdem die tatsächliche Antwort auf TV-/Smartphone-Hardware ausgewertet wurde.
