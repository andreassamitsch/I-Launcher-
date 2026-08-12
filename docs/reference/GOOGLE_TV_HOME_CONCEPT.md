# Google TV Home – analysiertes Referenzkonzept

> **Zweck:** Dauerhafte technische Referenz für I-Launcher-Home-, Rail-, Focus-, Navigation-, Glow- und Hero-Arbeiten.
>
> Diese Datei dokumentiert **beobachtete Architektur- und UX-Prinzipien** aus der Analyse einer bereitgestellten Google-TV-Launcher-APKM, aus aktuellen Google-TV-/TCL-Referenzansichten und aus deren Übertragung auf unsere eigene Compose-Implementierung. Sie enthält **keinen übernommenen proprietären Google-Code, keine Assets und keinen dekompilierten Quellcode**.

Stand der Referenzanalyse: 2026-08-12  
I-Launcher-Referenzcommit für den stabilen Row-Keyline-Fix: `4b9699e611faa31f38d2969d1fd847034d02cfc9`  
I-Launcher-Referenzcommit für Navigation + dynamischen Glow: `4933aa1ea4a5d568ab0b2a6b07c235eb0fbb8625`

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
4. Content-/Hero-Aktualisierung als Reaktion auf den Fokus,
5. die Top-Navigation als eigene Overlay-/Focus-Ebene außerhalb der Row-Geometrie.

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
- die Grenze zum äußeren Vertical-Scroll wird so kontrolliert, dass horizontale Kartenfokuswechsel keinen zusätzlichen Y-Scroll auslösen,
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
- visuelle Focus-Effekte sollen rendererfreundlich bleiben,
- die Glow-Farbe soll sichtbar zum aktuellen Artwork passen und nicht als feste Marken-/Diagnosefarbe erscheinen.

### Aktuelle I-Launcher-Umsetzung

Die Kartenfarbe wird nicht über eine neue Palette-Bibliothek oder eine zusätzliche Netzwerkquelle bestimmt. Stattdessen:

1. Für die **aktuell fokussierte** Karte wird über Coil eine kleine `32×18`-Software-Bitmap aus derselben Artwork-URI angefordert.
2. `allowHardware(false)` und explizite Zielmaße erlauben sichere CPU-seitige Pixelanalyse – auch für Quellen ohne positive Intrinsic-Größe.
3. Transparente, fast schwarze und nahezu weiße/entsättigte Pixel werden schwach oder gar nicht gewichtet.
4. Gesättigte Mitteltöne bestimmen die Aura stärker; Chroma wird leicht angehoben, die Helligkeit begrenzt.
5. Das Ergebnis wird pro Artwork-URI in einem kleinen lokalen Speicher-Cache gehalten.
6. Zwei Compose-`dropShadow()`-Ebenen erzeugen einen breiten und einen engeren farbigen Halo um die abgerundete Kartenform.
7. Der weiße Focus-Rahmen besitzt weiterhin einen langsamen, subtilen `Breath` über Alpha und Strichbreite.

Damit bleibt der Effekt inhaltsbezogen, ohne eine dauerhafte Pixelanalyse für unfokussierte/off-screen Karten zu betreiben. Große Live-Blur-/Radial-Layer wurden verworfen, weil sie im SwiftShader-Smoke reproduzierbar Renderer-/Screencap-Probleme erzeugten.

## 8. Google-TV-artige Top-Navigation

Die Top-Navigation ist **keine normale Zeile oberhalb des Home-Layouts**. Sie ist eine eigene Overlay-Ebene über dem Hero und darf dadurch die Hero-/Rail-Keyline weder beim Einblenden noch beim Ausblenden verändern.

### Struktur

Für die aktuelle I-Launcher-Funktionsmenge gilt:

- links: Branding + echte Content-Destinations (`Empfehlungen`, `Apps`),
- rechts: kompakte Utility-Aktionen (`Suche`, `Einstellungen`),
- keine erfundenen Tabs für Filme/Serien/Mediathek, solange diese keine echten Launcher-Destinations besitzen.

Google-TV-Tabsets unterscheiden sich nach Region/Version. Deshalb ist die **Hierarchie** die Referenz, nicht das blinde Kopieren jedes Google-Tabs.

### Selected vs. Focused

- die aktuell geöffnete Destination besitzt die helle kompakte Selected-Pill,
- eine andere nur fokussierte Destination erhält lediglich eine schwächere/transparente Focus-Fläche,
- Selection und Focus sind getrennte Zustände,
- beim Öffnen einer Destination erhält deren Selected-Element initial Fokus, damit nicht zwei Ziele gleichzeitig wie „aktiv“ wirken.

### Verhalten im Home-Content

Auf Home gilt das Muster aus der Google-TV-Referenzansicht:

- solange die Top-Navigation Fokus besitzt, ist die komplette Leiste sichtbar,
- bei `DOWN` in Hero/Content-Rails blendet die Leiste als Overlay aus,
- oben bleibt nur ein kleines dezentes Chevron (`^`) als Hinweis auf die erreichbare Navigation,
- die unsichtbare Leiste **bleibt im Fokusbaum an derselben Position**,
- `UP` kann sie dadurch wieder fokussieren; bei Fokusgewinn wird sie eingeblendet,
- **es findet keinerlei Reflow oder Änderung der Home-Keyline statt**.

Diese Trennung ist entscheidend: sichtbare Navigation darf niemals wieder als Padding/Spacer in die vertikale Home-Geometrie ein- oder ausgebaut werden.

## 9. Fokus- und Scroll-Eigentümerschaft als feste Regel

Bei künftigen Home-Änderungen zuerst bestimmen, **welche Ebene welche Bewegung besitzen soll**:

| Ebene | Zuständigkeit |
|---|---|
| Top Navigation | Destination-/Utility-Fokus als Overlay, kein Home-Reflow |
| Home Vertical Scroll / Row Stage | Y-Ausrichtung der aktiven Rail |
| Rail / `focusGroup()` | Eintritt/Verlassen der Row |
| `LazyRow` | horizontales X-Scrollen |
| Medienkarte | Fokus, Auswahl, Hero-/Metadaten-Update |
| Focus Decoration | Glow, Border, Breath, kleine Scale |
| Hero | Darstellung des aktuell fokussierten Inhalts |

Keine Ebene soll eine Bewegung „korrigieren“, die eigentlich einer anderen Ebene gehört.

## 10. Was bei späteren Fehlern zuerst prüfen

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
5. Sicherstellen, dass Nav-Sichtbarkeit nur Alpha/Decoration ändert und **nicht** die Layoutgeometrie.

### Wenn Glow abgeschnitten/farblos wird

1. Z-Reihenfolge der fokussierten Karte prüfen.
2. Draw-Space/Padding der Rail prüfen.
3. sicherstellen, dass Clip nur dort aktiv ist, wo tatsächlich gewollt,
4. keinen Glow durch Layout-Vergrößerung erzwingen,
5. prüfen, ob die kleine Software-Bitmap erfolgreich geladen wird und nicht auf die Fallback-Farbe fällt,
6. Glow-Fixtures mit klar unterschiedlichen Farben (blau/gold/violett) im Visual-Smoke vergleichen.

### Wenn die Top-Navigation Home verschiebt

1. prüfen, ob die Nav weiterhin Overlay in einem gemeinsamen `Box` ist,
2. kein Home-`padding(top=navHeight)` oder Spacer beim Sichtbarkeitswechsel hinzufügen,
3. Nav-Sichtbarkeit über Alpha/Focus-State ändern, nicht über Ein-/Ausbau aus dem Layout,
4. `UP`/`DOWN` im D-Pad-Video prüfen, nicht nur statische Screenshots.

## 11. Dinge, die wir bewusst **nicht** übernehmen

Google TV ist für I Launcher eine technische/UX-Referenz, nicht das Produktziel selbst.

Nicht übernehmen:

- Werbung oder Sponsored Content,
- automatisch rotierende Recommendation-/Werbe-Heros,
- Google-spezifische Content-Zwänge,
- proprietäre Assets oder dekompilierten Code,
- unnötige Netzwerkabhängigkeiten,
- nicht funktionale Fake-Tabs nur zur optischen Kopie,
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

## 12. Referenzen innerhalb des Repositories

- `AGENTS.md` – verbindliche Entwicklungsrichtlinien
- `ARCHITECTURE.md` – aktuelle I-Launcher-Architektur
- `app/src/main/java/com/andreassamitsch/ilauncher/ui/GoogleTvTopNavigation.kt` – aktuelle Top-Navigation
- `app/src/main/java/com/andreassamitsch/ilauncher/ui/components/FocusedCardEffects.kt` – Glow-/Breath-Implementierung
- `app/src/main/java/com/andreassamitsch/ilauncher/ui/home/HomeRowFocusAnchor.kt` – Row-Keyline-Implementierung
- `.github/workflows/tv-visual-smoke.yml` – D-Pad-/Screenshot-/Foreground-Regressionen
- PR #10 – Verlauf des Google-TV-Home-/Focus-Polish und der Fehleranalyse

## 13. Merksätze

> **Row owns Y, card owns focus/content, LazyRow owns X.**

> **Navigation is an overlay, not Home geometry.**

> **Google-TV-artige Home-Navigation bleibt ruhig, weil LEFT/RIGHT Inhalt in X bewegt, UP/DOWN die aktive Bühne in Y – und weder Glow noch Top-Navigation diese Eigentümerschaft verändern.**
