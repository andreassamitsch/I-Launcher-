# Joyn AT: Episodentext und Bildauflösung – Testplan (21.09.2026)

## Ausgangslage

„Bauer sucht Frau“ AT, Staffel 23, Folge 3: Android TV Watch Next hat das korrekte Joyn-AT-Branding, aber kürzeren Episodentext als die I-Launcher-Suche und ein klein profiliertes Joyn-Standbild.

## Verhalten

- Joyn bleibt alleinige Quelle für Logo, Identität und Bilder.
- I Launcher verwendet einen längeren TMDB-Episodentext nur bei genau einem anhand Herkunftsland AT und Staffel/Folge nachweisbaren Serientreffer. Sonst bleibt die Joyn-Kurzbeschreibung sichtbar.
- Joyn TV bevorzugt den größten in der GraphQL-Antwort explizit genannten LIVE_STILL für diese Folge. Nur aus der tatsächlich erhaltenen Bild-URL wird die originalgetreue URL ohne `/profile:` abgeleitet. Der Originalbild-Abruf muss erfolgreich sein und nachweislich mehr echte Bildpixel liefern. Sonst keine Umschaltung des Bildes.
- Die gewünschte 4K-Auflösung ist ein Ziel und wird nicht garantiert; das Quellbild kann kleiner sein.

## Gerätetest

1. Beide Apps aktualisieren. „Bauer sucht Frau“ AT S23 E3 über unsere Joyn-App abspielen und mindestens zwei Minuten laufen lassen.
2. Die Weiterschauen-Kachel und ihren Hero auf Bild, Serienlogo, Beschreibung und Wiedergabeposition prüfen.
3. Mehrdeutige deutsche und österreichische Serien mit gleichem Namen getrennt halten: niemals deutsches Logo/Artwork einmischen.
4. Bei weiterhin kleinem Bild die API-Bild-URL und die tatsächlich gemessenen Originalabmessungen protokollieren. Keine pauschale 4K-Zusage und kein künstliches Upscaling.
