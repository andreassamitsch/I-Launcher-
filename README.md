# I Launcher

I Launcher ist ein moderner, schneller und werbefreier Android-TV-Launcher. Er ist als Content Launcher gedacht: Der Benutzer wählt primär Inhalte und erst sekundär die App, über die sie wiedergegeben werden.

## Aktueller Stand

Der Launcher läuft als Android-TV-Home-App und verwendet Kotlin mit Jetpack Compose / Compose for TV.

Aktuell vorhanden:

- installierte TV-Apps über Androids reguläre Leanback-/PackageManager-Schnittstellen
- Android Watch Next / „Weiterschauen“ über `TvProvider`
- Preview Channels aus Apps über `TvProvider`
- TMDB-Anreicherung für Watch Next sowie Film-/Serien-/Episoden-Metadaten
- TMDB-Backdrops, Poster, Logos und Episodenbilder
- Trailer über TMDB-Video-Metadaten mit YouTube-Wiedergabe/Fallback
- Gigablue/OpenWebif-Anbindung für Bouquets, Sender, EPG und Streamauflösung
- XMLTV-EPG-Import mit lokalem Cache und Sender-Mapping
- TMDB-Anreicherung ausgewählter EPG-Programme
- integrierter Live-TV-Player mit Media3
- globale Local-First-Suche über Watch Next, App-Kanäle, EPG und installierte Apps; TMDB ergänzt die Treffer
- Google-TV-inspirierte Suchoberfläche mit breiter Suchfläche, separater Sprachsuche, auswählbaren Beispielanfragen, kompakten Ergebnisfiltern und 16:9-Rails
- Google-TV-inspirierter Home-Hero und kompakte Content-Rails ohne Werbung
- adaptive `Alle Apps`-Ansicht für TV-Breiten
- lokale Home-Reihenfolge und App-Reihenfolge
- Debug-/Diagnosepfade und signierte Development-APK mit In-App-Updater
- deterministische 1920×1080 Android-TV-Visual-Smoke-Screenshots für Home und Suche

## Produktprinzipien

- Content vor Apps
- TV/D-Pad als primäre Bedienform
- Local First und Datenschutz
- keine Werbung
- keine unnötige Drittanbieterabhängigkeit
- bestehende Android-TV-Schnittstellen bevorzugen
- Gigablue möglichst direkt über OpenWebif integrieren
- funktionierende Bereiche nicht ohne Grund umbauen

## Home

Home verwendet einen ruhigen, lokalen Hero. Seine Priorität ist:

1. erster Watch-Next-Inhalt
2. erster sichtbarer Preview-Program-Inhalt
3. neutraler Fallback

Der Hero rotiert nicht automatisch durch Netzwerkempfehlungen. Live TV übernimmt den Hero nur durch aktiven Fokus. Apps ändern den Medien-Hero nicht.

Unterhalb des Hero liegen frei sortierbare Content-Rails. Watch Next, Preview Channels und Live TV verwenden ein gemeinsames kompaktes ungefähr 16:9-Raster. Nur die Artwork-Fläche ist fokussierbar; Titel bleiben ruhig darunter. Die Apps-Reihe bleibt ein kompakter Icon-Dock mit Labels nur bei Fokus beziehungsweise Verschiebemodus.

## Suche

Die Suche besitzt zwei Zustände:

- **Leere Suche / Discover:** breite Suchfläche mit separatem Mikrofon, auswählbare Beispielanfragen und anschließend TMDB-Browse-Reihen wie Trends oder Genres.
- **Explizite Suche:** lokale Treffer bleiben getrennt und vorrangig als `Weiterschauen`, `Aus deinen Apps`, `Im TV` und `Apps`; TMDB ergänzt als `Filme & Serien`.

Die Filter `Alle`, `Filme & Serien`, `TV` und `Apps` filtern nur die bereits vorhandenen sichtbaren Ergebnisgruppen und verändern weder Suchbackend noch Quellenreihenfolge. Ergebnis- und Browse-Karten verwenden dieselbe kompakte 16:9-Sprache wie Home. D-Pad-Fokus liegt auf dem Artwork; Titel und Sekundärtext bleiben außerhalb der Fokusfläche.

## Watch Next

Watch Next verwendet zuerst die reguläre Android-TV-/TvProvider-Schnittstelle. App-spezifische CloudStream-Logik wird nicht eingeführt, solange Android die benötigten Daten liefert.

Die von Android gelieferte Reihenfolge wird nicht ohne Grund verändert.

Kurzes OK startet den vorhandenen Source-/Playback-Intent. `INFO` beziehungsweise langes OK öffnet Details. Die Freigabe einer langen OK-Taste wird abgefangen, damit beim Öffnen der Detailseite nicht versehentlich die dortige erste Aktion ausgelöst wird.

## Preview Channels

Preview Channels werden provider-neutral aus `TvProvider` gelesen. Sichtbare Kanäle können lokal ein- oder ausgeblendet werden. Die Reihenfolge der Programme wird nicht willkürlich verändert.

## TMDB

TMDB dient zur Metadatenanreicherung und Discovery. Es wird für Filme, Serien, Episoden, Bilder und Trailer-Metadaten verwendet.

Die Suche bleibt dennoch Local First: lokale Quellen werden zuerst verarbeitet; TMDB ergänzt. Bei leerer Suche dürfen gecachte/geladene TMDB-Browse-Reihen als Discover-Inhalt erscheinen.

## Gigablue / OpenWebif

Gigablue X3 wird möglichst direkt über Enigma2/OpenWebif integriert. DreamTV, TiviMate oder ähnliche Apps sind keine Voraussetzung.

Vorhanden sind:

- Verbindungskonfiguration
- Bouquets
- Sender
- Stream-Auflösung
- EPG
- XMLTV-Ergänzung
- Sender-Mapping
- TMDB-EPG-Anreicherung
- Media3-Live-TV-Wiedergabe

Der aktive Sender wird über die stabile Enigma2-`serviceReference` gehalten. Periodische Metadaten-/EPG-Refreshes dürfen den Player nicht auf den ursprünglich geöffneten Sender zurücksetzen.

## Live-TV-Player

Der integrierte Player verwendet Media3. Normales OK öffnet die Senderübersicht und hält sie bewusst offen; Zurück schließt zuerst nur die Übersicht. Langes OK öffnet den EPG. Hoch/Runter beziehungsweise CH+/CH− sind auf TV-gerechte Sendernavigation ausgelegt.

EPG-Programme werden über `serviceReference + startUtcMillis` identifiziert, damit asynchrone TMDB-Anreicherung die Auswahl nicht verliert.

## Trailer

Trailer werden bevorzugt über TMDB-Video-Metadaten aufgelöst. Ist eine konkrete YouTube-ID vorhanden, wird sie direkt verwendet. Ohne konkrete ID dient die YouTube-Suche als Fallback. Es findet keine Stream-Extraktion statt.

## Apps

Die Home-App-Reihe ist bewusst kompakt. `Alle Apps` nutzt dasselbe App-Modell, aber ein adaptives Grid mit dauerhaft sichtbaren Labels.

## TV-Visual-Smoke

Die Debug-Variante enthält eine deterministische `UiPreviewActivity`. Der GitHub-Workflow `TV Visual Smoke` startet einen API-34-Android-TV-Emulator in 1920×1080 und erzeugt reproduzierbare Screenshots ohne Abhängigkeit von Netzwerk, TvProvider oder OpenWebif.

Aktuell werden Home-Start, Home-Scrollzustände, Search-Discover, Search-Query und ein fokussierter Suchtreffer aufgenommen. Diese Screenshots dienen zur Geometrie-/Fokusprüfung vor dem realen TCL-Gerätetest. Sie ersetzen keinen Hardwaretest.

## Build und Tests

Vor einer Development-APK laufen mindestens:

```text
:app:testDebugUnitTest
:app:assembleDebug
```

Ein erfolgreicher Build gilt nicht als Hardwaretest. D-Pad-/Fokusverhalten, Bildausschnitt, Live-TV-Verhalten und OEM-spezifische Launcher-Funktionen werden zusätzlich auf realer TV-Hardware geprüft.

## Paketkennung

```text
com.andreassamitsch.ilauncher
```

Die Application ID bleibt stabil, damit Updates installierbar bleiben.

## Projektregeln

Die verbindlichen Entwicklungsregeln stehen in [`AGENTS.md`](AGENTS.md). Architekturdetails stehen in [`ARCHITECTURE.md`](ARCHITECTURE.md), die weitere Planung in [`ROADMAP.md`](ROADMAP.md).
