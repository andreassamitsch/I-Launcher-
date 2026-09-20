# Joyn / I Launcher: Watch Next Episodenmetadaten – Gerätetest

Stand: 20.09.2026. Codefix im Branch `feature/joyn-tv-client`.

## Reproduktionsfall

„Bauer sucht Frau“ erscheint in I Launcher unter „Weiterschauen“, aber ohne Staffel-/Folgenanzeige; Hero und Karte verwenden kein nachweislich folgenspezifisches Bild.

## Umsetzung

- Joyn-Resume-Abgleich bewahrt bereits lokal vorhandene Episode-Koordinaten und das zugehörige Bild für dieselbe Asset-/Video-ID; Daten anderer Episoden werden nicht übernommen.
- Bei Episoden wird das Thumbnail gegenüber einem möglichen Serien-Backdrop bevorzugt; zusätzliche direkt gelieferte Staffel-/Folgenfelder werden gelesen.
- I Launcher hält vom Anbieter als Episode gekennzeichnete Watch-Next-Einträge auch dann als Episode, wenn TMDB zunächst nur die Elternserie findet; die vom Anbieter gelieferte Bild-URI kann vor einem allgemeinen Serien-Backdrop verwendet werden.

## Noch auf Android TV zu prüfen

1. Joyn TV und I Launcher auf die hierauf folgenden signierten Testversionen aktualisieren.
2. In Joyn „Bauer sucht Frau“ gezielt über Staffel und Episode starten; mindestens zwei Minuten abspielen; in I Launcher „Weiterschauen“ prüfen.
3. Nach einem Konto-Resume-Refresh erneut prüfen, ob Staffel/Folge und Episodenbild erhalten bleiben.
4. Wenn schon die Joyn-Episode keine Koordinaten liefert, exakte Video-ID/Asset-ID sowie die tatsächlich von Joyn gelieferte Episodenmetadatenstruktur untersuchen; keine Folgen aus Titel oder Sendungsreihenfolge erraten.

Der Codefix ist nicht als bestätigter Live-Hardware-Test zu verstehen.
