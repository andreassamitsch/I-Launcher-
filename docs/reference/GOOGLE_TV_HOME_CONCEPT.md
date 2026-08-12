# Google TV Home – analysiertes Referenzkonzept

> **Zweck:** Dauerhafte technische Referenz für I-Launcher-Home-, Rail-, Focus- und Hero-Arbeiten.
>
> Diese Datei dokumentiert **beobachtete Architektur- und UX-Prinzipien** aus der Analyse einer bereitgestellten Google-TV-Launcher-APKM sowie deren Übertragung auf unsere eigene Compose-Implementierung. Sie enthält **keinen übernommenen proprietären Google-Code, keine Assets und keinen dekompilierten Quellcode**.

Stand der Referenzanalyse: 2026-08-12  
I-Launcher-Referenzcommit für den stabilen Row-Keyline-Fix: `4b9699e611faa31f38d2969d1fd847034d02cfc9`

## 1. Warum diese Referenz existiert

Bei der Home-Navigation trat in I Launcher ein reproduzierbarer Fehler auf: Beim horizontalen D-Pad-Wechsel innerhalb einer Rail bewegte sich die komplette Reihe vertikal nach oben/unten.

Statt das Verhalten weiter empirisch mit Gegen-Scrolls oder Timing-Workarounds zu korrigieren, wurde die bereitgestellte Google-TV-Launcher-APKM auf APK-/DEX-/Klassen-/Ressourcenebene untersucht und mit dem bekannten AndroidX-Leanback-Modell abgeglichen.

Die wichtigste Erkenntnis ist nicht ein einzelner Zahlenwert oder eine Animation, sondern die **Eigentümerschaft der Achsen**:

- die **vertikale Home-Struktur besitzt die Row-Keyline**,
- die **horizontale Rail besitzt die X-Navigation ihrer Karten**,
- ein LEFT/RIGHT-Wechsel innerhalb derselben Rail darf die vertikale Home-Position nicht neu ausrichten,
- nur der tatsächliche Eintritt in eine andere vertikale Reihe darf die Home-Keyline verändern.

Dieses Prinzip ist für künftige Home-Änderungen als Referenz beizubehalten.

## 2. Relevante Signaturen aus der Google-TV-Launcher-Analyse

In der analysierten APKM waren u. a. folgende Begriffe/Klassen sichtbar und für das Verhalten relevant:

- `ChannelHorizontalGridView`
- `ChannelVerticalGridView`
- `RowsFragment`
- `GridViewAlignmentOverrides`
- `extraSpaceBeforeKeyLine`
- `extraSpaceAfterKeyLine`
- `FocusFrameDecoration`
- `GlowDrawable`

Diese Namen sind **Hinweise auf die Architektur**, keine Aufforderung zur 1:1-Reimplementierung oder Codeübernahme.

Das erkennbare Leanback-artige Modell trennt:

1. vertikale Row-/Grid-Ausrichtung,
2. horizontale Fokusbewegung innerhalb einer Row,
3. Focus-Decoration/Glow als visuelle Ebene,
4. Content-/Hero-Aktualisierung als Reaktion auf den Fokus.

## 3. Zentrales Keyline-Modell

### 3.1 Eine vertikale Bühne für die aktive Rail

Die aktive Content-Reihe liegt auf einer stabilen vertikalen Bühne bzw. **Keyline**.

Beim Wechsel `UP/DOWN`:

1. Fokus betritt eine andere Rail.
2. Die vertikale Home-Fläche richtet diese neue Rail auf die definierte Keyline aus.
3. Danach bleibt die Y-Position stabil, bis eine andere Rail betreten wird.

Beim Wechsel `LEFT/RIGHT`:

1. Fokus wechselt nur zwischen Karten derselben Rail.
2. Die Rail darf bei Bedarf horizontal scrollen.
3. Hero/Metadaten dürfen auf den neu fokussierten Inhalt wechseln.
4. **Die äußere vertikale Home-Fläche darf keinen neuen Scrollauftrag erhalten.**

### 3.2 Keine Karten-Keyline für Y

Die einzelne Karte besitzt nicht die vertikale Home-Ausrichtung. Würde jede fokussierte Karte selbst `bringIntoView`, `animateScrollTo` oder eine vergleichbare Y-Ausrichtung bis zum äußeren Scrollcontainer propagieren, entsteht genau das beobachtete Auf-/Ab-Hüpfen.

Daher gilt für I Launcher:

> **Row owns Y, card owns focus/content, LazyRow owns X.**

### 3.3 Keyline ist ein relatives Layoutkonzept

Die im 1920×1080-Smoke gemessene stabile Kartenoberkante von `647 px` ist **nur ein Regressionsergebnis dieses Layouts** und darf nicht als universeller Pixelwert hart codiert werden.

Die Keyline muss aus dem Compose-Layout bzw. den verwendeten `Dp`-/Viewport-Proportionen entstehen und auch bei anderen TV-Auflösungen/Dichten sinnvoll bleiben.

## 4. Übertragung auf I Launcher / Compose

Der stabile I-Launcher-Ansatz nach der Analyse:

### `AnchoredHomeRow`

- jede Rail wird als gemeinsame `focusGroup()` behandelt,
- vertikale Ausrichtung wird nur beim Übergang `hasFocus: false -> true` ausgelöst,
- Fokuswechsel zwischen Kindkarten derselben Rail lösen **keine weitere vertikale Ausrichtung** aus,
- die Karten dürfen weiterhin Hero-/Content-State aktualisieren.

### Äußerer Home-Scroll

- automatische descendant-`bringIntoView`-Propagation darf nicht die vertikale Home-Position bei LEFT/RIGHT verändern,
- die Grenze zum äußeren Vertical-Scroll muss daher so kontrolliert werden, dass horizontale Kartenfokuswechsel keinen zusätzlichen Y-Scroll auslösen,
- der explizite Row-Entry-Keyline-Mechanismus bleibt alleiniger Besitzer der vertikalen Fokusausrichtung.

### Innere `LazyRow`

- horizontales Bring-Into-View bleibt normal aktiv,
- eine weit rechts/links liegende Karte darf in X ins Bild gescrollt werden,
- diese X-Bewegung darf nicht an den vertikalen Parent gekoppelt werden.

## 5. Regressionkriterium für horizontale Navigation

Für jede Home-Rail gilt:

- nach Eintritt in die Rail muss sie auf ihrer Keyline ruhen,
- mehrere schnelle `LEFT/RIGHT`-Schritte dürfen **0 sichtbare Y-Drift** der kompletten Rail erzeugen,
- Glow, Focus-Zoom, Hero-Wechsel oder horizontaler LazyRow-Scroll dürfen die Row-Y-Position nicht beeinflussen,
- Y-Bewegung ist nur bei echtem `UP/DOWN`-Wechsel zwischen Rows zulässig.

Der Visual-Smoke für Commit `4b9699e611faa31f38d2969d1fd847034d02cfc9` ergab bei 1920×1080:

- erste Rail, sechs horizontale Zustände: `647, 647, 647, 647, 647, 647 px`,
- zweite Rail, Start + zwei RIGHT-Schritte: `647, 647, 647 px`,
- damit `0 px` Y-Drift in den geprüften horizontalen Sequenzen.

Diese Sequenzen sollen als Regressionstest erhalten bleiben, auch wenn sich die konkrete Keyline später optisch ändert.

## 6. Hero- und Rail-Komposition

Aus der Google-TV-artigen Home-Komposition und unseren bisherigen Tests ergibt sich folgendes Zielbild:

- Hero ist keine abgeschlossene Karte oberhalb des Contents,
- Hero/Backdrop zeichnet edge-to-edge hinter der oberen Home-Fläche,
- die erste Content-Rail überlappt den unteren Hero-Bereich,
- der Übergang vom Hero zur Content-Fläche erfolgt über Verlauf/Scrim statt harte Boxgrenze,
- bildfüllende breite Hero-Artworks werden bei I Launcher `TopCenter` ausgerichtet,
- Text und Navigation verwenden lokale Safe-Area-Abstände; es gibt keinen globalen Außenrahmen um die komplette Home-Seite,
- tieferes vertikales Scrollen bringt jede aktive Rail auf dieselbe visuelle Bühne.

Für die letzte Home-Rail muss genügend **Bottom-Scroll-Reserve** vorhanden sein, damit auch sie dieselbe Keyline erreichen kann. Die letzte Reihe darf nicht nur deshalb tiefer stehen, weil das Scrollende zu früh erreicht ist.

## 7. Fokusdarstellung / Glow

Die Google-TV-Analyse zeigte eine eigene visuelle Focus-/Glow-Ebene (`FocusFrameDecoration`, `GlowDrawable`). Das relevante Prinzip für uns:

- Fokusdarstellung ist **Dekoration**, keine Layoutänderung,
- Glow darf die Fokus-Hitbox nicht vergrößern,
- Glow/Border dürfen keinen zusätzlichen Fokuspunkt erzeugen,
- Scale/Glow dürfen die Row-Keyline nicht beeinflussen,
- fokussierte Karten brauchen ausreichend Draw-Space bzw. korrekte Z-Reihenfolge, damit Nachbarkarten den Halo nicht abschneiden,
- visuelle Focus-Effekte sollen möglichst rendererfreundlich bleiben.

In I Launcher hat sich ein content-farbiger, shadow-basierter Glow mit subtiler Breath-Kontur als stabiler erwiesen als große Blur-/Radial-Layer, die im SwiftShader-Smoke reproduzierbar Rendererprobleme verursachten.

## 8. Fokus- und Scroll-Eigentümerschaft als feste Regel

Bei künftigen Home-Änderungen zuerst bestimmen, **welche Ebene welche Bewegung besitzen soll**:

| Ebene | Zuständigkeit |
|---|---|
| Home Vertical Scroll / Row Stage | Y-Ausrichtung der aktiven Rail |
| Rail / `focusGroup()` | Eintritt/Verlassen der Row |
| `LazyRow` | horizontales X-Scrollen |
| Medienkarte | Fokus, Auswahl, Hero-/Metadaten-Update |
| Focus Decoration | Glow, Border, Breath, kleine Scale |
| Hero | Darstellung des aktuell fokussierten Inhalts |

Keine Ebene soll eine Bewegung „korrigieren“, die eigentlich einer anderen Ebene gehört.

## 9. Was bei späteren Fehlern zuerst prüfen

### Wenn eine Rail bei LEFT/RIGHT wieder vertikal springt

1. Wird irgendwo pro Kartenfokus erneut `animateScrollTo()`/`scrollTo()` auf dem äußeren Home-Scroll ausgelöst?
2. Propagiert `bringIntoView` der Kindkarte wieder bis zum vertikalen Parent?
3. Hat eine Focus-Scale oder neue Decoration die gemessene Layoutgröße statt nur die Darstellung verändert?
4. Ist die Rail noch eine gemeinsame `focusGroup()` oder wurde die Fokusgrenze versehentlich aufgespalten?
5. Wird die Keyline beim Kindfokus statt nur beim Row-Entry berechnet?

### Wenn UP/DOWN falsch positioniert

1. Row-Top in Root/Viewport korrekt messen.
2. Ziel-Keyline relativ zum aktuellen `ScrollState` berechnen.
3. Scrollwert gegen `0..maxValue` begrenzen.
4. Prüfen, ob Bottom-Reserve für die letzten Rows ausreicht.
5. Fokus-/Nav-Visibility-Transition vor der endgültigen Row-Ausrichtung vollständig setzen lassen.

### Wenn Glow abgeschnitten wird

1. Z-Reihenfolge der fokussierten Karte prüfen.
2. Draw-Space/Padding der Rail prüfen.
3. sicherstellen, dass Clip nur dort aktiv ist, wo tatsächlich gewollt,
4. keinen Glow durch Layout-Vergrößerung erzwingen.

## 10. Dinge, die wir bewusst **nicht** übernehmen

Google TV ist für I Launcher eine technische/UX-Referenz, nicht das Produktziel selbst.

Nicht übernehmen:

- Werbung oder Sponsored Content,
- automatisch rotierende Recommendation-/Werbe-Heros,
- Google-spezifische Content-Zwänge,
- proprietäre Assets oder dekompilierten Code,
- unnötige Netzwerkabhängigkeiten,
- komplexe Effekte, wenn sie D-Pad-Stabilität oder Performance verschlechtern.

Priorität bleibt gemäß `AGENTS.md`:

1. zuverlässige Android-TV-Funktion,
2. hervorragende D-Pad-Bedienung,
3. Performance,
4. Wartbarkeit,
5. saubere Architektur,
6. Local First / Datenschutz,
7. geringe Drittanbieterabhängigkeit,
8. Optik.

## 11. Referenzen innerhalb des Repositories

- `AGENTS.md` – verbindliche Entwicklungsrichtlinien
- `ARCHITECTURE.md` – aktuelle I-Launcher-Architektur
- `app/src/main/java/com/andreassamitsch/ilauncher/ui/home/HomeRowFocusAnchor.kt` – aktuelle Row-Keyline-Implementierung
- `.github/workflows/tv-visual-smoke.yml` – D-Pad-/Screenshot-Regressionen
- PR #10 – Verlauf des Google-TV-Home-/Focus-Polish und der Keyline-Fehleranalyse

## 12. Merksatz

> **Google-TV-artige Home-Navigation bleibt ruhig, weil die vertikale Row-Keyline der Row gehört – nicht der jeweils fokussierten Karte. LEFT/RIGHT bewegt Inhalt in X; UP/DOWN bewegt die aktive Bühne in Y.**
