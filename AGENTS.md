# I Launcher – verbindliche Entwicklungsrichtlinien

Diese Datei ist die zentrale technische und produktseitige Quelle der Wahrheit für das Repository **I Launcher**.

Vor jeder Entwicklungsaufgabe, Codeänderung, Architekturentscheidung, Fehlerbehebung oder Pull-Request-Arbeit in diesem Repository muss die aktuelle Version dieser Datei gelesen werden. Zusätzlich sind `README.md`, `ROADMAP.md` und `ARCHITECTURE.md` zu prüfen, sofern sie für die Aufgabe relevant sind. Bei Widersprüchen gilt diese `AGENTS.md` vor älteren Chat-Inhalten oder Erinnerungen.

## 1. Produktziel

I Launcher ist ein moderner, schneller und werbefreier Android-TV-Launcher, der als primäre TV-Oberfläche verwendet werden kann.

Grundprinzip: **Content Launcher statt bloßer App Launcher.**

Der Benutzer soll primär Inhalte auswählen und erst sekundär die App, über die diese wiedergegeben werden. Die Oberfläche darf sich an guten Bedienprinzipien von Google TV orientieren, soll aber ruhiger, lokaler und ohne Werbung, Sponsored Content oder Google-eigene Empfehlungszwänge funktionieren.

## 2. Technischer Stack

Bevorzugter Stack:

- Kotlin
- Jetpack Compose
- Compose for TV
- AndroidX
- Coroutines / Flow
- Room
- Hilt
- Retrofit / OkHttp
- Coil
- Media3

Keine Flutter-Basis.

Neue Bibliotheken nur hinzufügen, wenn sie einen klaren Mehrwert bieten. Abhängigkeiten und Versionen nicht raten, wenn sie aus offizieller Dokumentation oder Release Notes geprüft werden können.

## 3. Open-Source-Referenzen

Bestehende Projekte dürfen als Referenz analysiert werden, insbesondere:

- Arc Launcher
- FLauncher
- LauncherCompose
- AOSP / Android TvProvider
- offizielle Android-TV- und Compose-for-TV-Samples

Code nur übernehmen, wenn die Lizenz kompatibel ist und die Herkunft sauber dokumentiert werden kann. Bevorzugt eigene, verständliche Kotlin-Implementierungen entwickeln.

## 4. Kernfunktionen

Langfristig soll I Launcher unterstützen:

- Android-TV-Launcher/Home-App
- installierte Apps
- Android Watch Next / „Weiterschauen“
- Preview Channels aus Apps
- TMDB-Anreicherung für Filme, Serien und Episoden
- Poster, Backdrops, Logos und Episodenbilder
- Trailer vorzugsweise über TMDB + YouTube
- Gigablue X3 direkt über Enigma2/OpenWebif
- Bouquets, Sender, EPG und Streams
- EPG-Anreicherung über TMDB
- integrierten Live-TV-Player mit Media3
- globale Suche
- einfache Google-TV-artige UI ohne Werbung

## 5. Architekturgrundsatz

Die UI darf nicht direkt von einzelnen Datenquellen abhängen. Datenquellen werden über Provider/Repositories normalisiert und auf gemeinsame interne Modelle abgebildet.

Zielstruktur:

```text
I-Launcher
├── app
├── core
│   ├── model
│   ├── database
│   ├── network
│   ├── ui
│   ├── navigation
│   └── util
├── provider
│   ├── androidtv
│   ├── tmdb
│   ├── youtube
│   ├── enigma2
│   ├── kodi
│   └── cloudstream
├── feature
│   ├── home
│   ├── watchnext
│   ├── details
│   ├── livetv
│   ├── epg
│   ├── apps
│   ├── search
│   └── settings
└── playback
```

Nicht jede Struktur muss sofort als separates Gradle-Modul umgesetzt werden. Frühere Phasen dürfen bewusst einfacher sein, solange die Grenzen im Code erkennbar bleiben und eine spätere Modularisierung nicht unnötig erschwert wird.

## 6. Gemeinsames Medienmodell

Filme, Serien, Episoden und Inhalte aus verschiedenen Apps sollen intern vereinheitlicht werden.

Ein gemeinsames Modell soll langfristig u. a. enthalten:

- interne ID
- Content-Typ
- Titel / Originaltitel / Untertitel
- Beschreibung
- Erscheinungsjahr
- TMDB-ID
- Staffel / Episode / Episodentitel
- Poster / Backdrop / Logo / Episodenbild
- Wiedergabeposition / Gesamtdauer / Fortschritt
- letzte Wiedergabe
- Quell-App / Package Name
- Deep Link / Intent
- Trailer
- Metadatenqualität / Resolver Confidence

Die Benutzeroberfläche soll nicht wissen müssen, ob ein Inhalt ursprünglich von CloudStream, Kodi, Jellyfin, Plex, einer Streaming-App oder Android Watch Next stammt.

## 7. Watch Next / Weiterschauen

Watch Next ist eine zentrale Datenquelle.

Auf dem Zielgerät funktioniert Watch Next bereits mit Arc Launcher und liefert unter anderem Einträge von CloudStream. Deshalb zuerst die reguläre Android-TV/TvProvider-Schnittstelle verwenden.

Keine app-spezifische CloudStream-Integration entwickeln, solange Android bereits die benötigten Daten zuverlässig liefert.

Die Reihenfolge der Watch-Next-Einträge nicht ohne nachvollziehbaren Grund verändern oder umkehren. Zunächst die von Android bzw. der Quell-App vorgesehene Reihenfolge übernehmen.

Darstellung langfristig:

- Backdrop oder Episodenbild
- Titel
- ggf. Staffel/Episode
- Fortschrittsbalken
- verbleibende Zeit, sofern verfügbar
- Quell-App optional dezent

Auswahl startet den vorhandenen Deep Link / Intent der Quell-App.

## 8. Android-TV-Kanäle

Preview Channels und Preview Programs installierter Apps unterstützen.

Inhalte verschiedener Apps können als eigene Reihen auf der Startseite erscheinen. Der Benutzer soll später auswählen können, welche Kanäle angezeigt werden und in welcher Reihenfolge.

## 9. TMDB

TMDB ist die bevorzugte zentrale Metadatenquelle für Filme, Serien und Episoden.

Verwenden für:

- Titel / Originaltitel
- Beschreibung
- Erscheinungsjahr
- Genres / Laufzeit
- Poster / Backdrops / Logos
- Cast / Bewertungen
- externe IDs
- Staffel / Episode / Episodentitel
- Episodenbeschreibung / Episodenbild / Air Date
- Trailerinformationen

TMDB-Attribution und Lizenzbedingungen müssen eingehalten werden.

## 10. TMDB Resolver und Cache

Nicht bei jedem Anzeigen erneut TMDB durchsuchen.

Ein zentraler Resolver normalisiert Quelltitel und ermittelt bei ausreichender Sicherheit die passende TMDB-ID. Erfolgreiche Mappings in Room speichern.

Beispiel:

```text
Fallout S02E04
→ normalize
→ Serie Fallout / Staffel 2 / Episode 4
→ TMDB-Suche
→ TMDB-ID
→ Episodendaten
→ lokaler Cache
```

Bei unsicherem Matching lieber Originaldaten anzeigen als falsche TMDB-Daten. Confidence-System verwenden.

## 11. Zusammenführen gleicher Inhalte

Wenn derselbe Inhalt eindeutig aus mehreren Quellen erkannt wird, soll langfristig eine gemeinsame Inhaltskarte möglich sein. TMDB-ID kann als gemeinsame Identität dienen.

Keine aggressiven Zusammenführungen bei unsicherem Matching.

## 12. Trailer

Priorität:

1. TMDB Video-Metadaten
2. vorhandene YouTube-ID
3. nur bei Bedarf YouTube-Suche

YouTube-Suchen und Ergebnisse cachen. API-Aufrufe minimieren.

## 13. Gigablue / Enigma2 / OpenWebif

Zielreceiver: **Gigablue X3 mit Enigma2/OpenATV/OpenWebif**.

Direkte Integration bevorzugen. Keine Abhängigkeit von dreamTV, TiviMate oder ähnlichen Apps, sofern OpenWebif die benötigte Funktion direkt bereitstellt.

Eigener Enigma2/OpenWebif-Provider soll langfristig unterstützen:

- Receiver-Verbindung / Authentifizierung
- Bouquets
- Senderlisten
- Senderlogos, soweit verfügbar
- Service References
- aktuelles / nächstes Programm
- EPG
- Senderstream
- später ggf. Timer und Aufnahmen

## 14. Live-TV-Startseite und EPG

Eigene Reihe **„Jetzt im TV“**.

Nicht nur Senderlogos anzeigen, sondern möglichst Senderlogo, aktuelle Sendung, Start/Ende, Fortschritt und ein passendes Bild.

EPG-Daten kommen vom Gigablue/OpenWebif und können durch den TMDB-Resolver mit Postern, Backdrops oder Episodenbildern angereichert werden.

Weitere mögliche Reihen: „Gleich im TV“, „Heute Abend“, „Meine Sender“.

## 15. Live-TV-Wiedergabe

Langfristig Live-TV direkt im Launcher mit Media3 abspielen. Quelle ist der OpenWebif/Enigma2-Stream.

Die Architektur muss internen Live-TV-Playback ermöglichen, auch wenn der Player erst in einer späteren Phase vollständig umgesetzt wird.

## 16. Startseite und UX

Die Startseite soll ruhig, schnell und übersichtlich sein.

Mögliche Reihenfolge:

1. Hero / aktuell relevanter Inhalt
2. Weiterschauen
3. Jetzt im TV
4. App-Kanäle
5. Neue Folgen
6. Filme / Serien
7. Apps

Keine endlosen Recommendation-Reihen. Keine Werbung. Keine Sponsored Cards.

Hero-Bereich maximal ein prominenter Inhalt gleichzeitig, kein automatisch durchlaufendes Werbekarussell.

## 17. D-Pad / Fernbedienung

TV-UX hat höchste Priorität.

Alle Ansichten müssen vollständig mit D-Pad, OK, Zurück und Home bedienbar sein.

Besonders beachten:

- vorhersehbare Focus-Reihenfolge
- Focus darf nicht verloren gehen
- Position beim Zurückkehren erhalten
- keine unnötigen Focus-Sprünge
- horizontale Listen sauber scrollen
- kein Touch voraussetzen
- Animationen dezent und schnell

## 18. Performance und Offline

Launcher muss sehr schnell starten.

Grundregel: Startseite zuerst aus lokalen Daten anzeigen, Netzwerkdaten anschließend aktualisieren.

Nicht beim Start auf TMDB, kompletten EPG oder YouTube warten.

Caching intensiv nutzen. Flow-basierte UI-Updates bevorzugen.

Auch ohne Internet müssen Launcher, Apps, lokale Caches und Gigablue im LAN soweit möglich funktionieren.

## 19. Bilder

Bevorzugte Reihenfolge:

Film:
1. TMDB Backdrop
2. TMDB Poster
3. Quellbild

Serie/Episode:
1. Episoden-Still
2. Serien-Backdrop
3. Serienposter
4. Quellbild

Geeignete Bildgrößen laden, keine unnötig großen Originale.

## 20. Suche

Langfristig globale Suche über installierte Apps, Watch Next, TMDB, Gigablue-EPG und später weitere Provider.

Suchresultate auf gemeinsame Modelle normalisieren.

## 21. Apps

Eigene App-Ansicht mit installierten TV-Apps. Später Favoriten, Reihenfolge, Ausblenden und Kategorien.

Auf der Startseite müssen nicht zwingend alle Apps gezeigt werden.

## 22. Einstellungen und Diagnose

Langfristig Einstellungen für Startseitenreihen, Erscheinungsbild, Watch Next, App-Kanäle, TMDB, Gigablue und Entwicklerdiagnose.

Diagnosekategorien z. B.:

- WATCH_NEXT
- TV_PROVIDER
- TMDB_RESOLVER
- OPENWEBIF
- EPG
- PLAYER
- FOCUS
- APP_LAUNCH

Keine Tokens, Passwörter oder vollständigen privaten URLs loggen.

## 23. Datenschutz und Sicherheit

Grundprinzip: **Local First**.

Keine Werbe-SDKs. Keine Analytics-SDKs standardmäßig. Keine Telemetrie ohne ausdrückliche Zustimmung.

Gigablue-Kommunikation bleibt lokal. Externe Kommunikation möglichst nur zu ausdrücklich benötigten Diensten wie TMDB und YouTube.

Keine Zugangsdaten oder API-Keys im Repository speichern. Keine Secrets in Logs ausgeben.

## 24. Entwicklungsregeln

Nicht raten, wenn Verhalten durch Quellcode, Android-Dokumentation, API-Antworten oder Logs überprüfbar ist.

Bei Fehlern:

1. Ursache analysieren
2. relevanten Code prüfen
3. tatsächliches Verhalten / Logs / API prüfen
4. erst dann ändern
5. anschließend Build und Tests durchführen

Keine zufälligen Workarounds. Bestehende funktionierende Bereiche nicht unnötig umbauen.

## 25. GitHub-Workflow

Bei größeren Änderungen:

1. bestehenden Code analysieren
2. Arbeitsziel definieren
3. Feature-/Fix-Branch verwenden
4. Feature/Fix implementieren
5. Build durchführen
6. Tests ausführen
7. Regressionen prüfen
8. verständliche Commits
9. Pull Request mit nachvollziehbarer Beschreibung

Keine großen unkontrollierten Änderungen direkt auf `main`.

Commit-Beispiele:

- `feat: add Android TV watch next provider`
- `feat: add TMDB media resolver`
- `feat: add OpenWebif bouquet support`
- `fix: preserve watch next ordering`
- `fix: restore focus after returning from details`
- `refactor: extract unified media repository`

## 26. Tests

Neue Funktionen soweit sinnvoll automatisiert testen.

Mindestens relevant:

- Resolver / Parsing
- Watch-Next-Mapping
- OpenWebif API Parsing
- TMDB-Matching
- Datenbankmigrationen

Bei UI zusätzlich reale D-Pad-Navigation, Back-Navigation, TV-Auflösung und Focus-Verhalten prüfen.

## 27. Build und Hardwaretests

Eine Funktion gilt erst als fertig, wenn der Build erfolgreich ist und relevante Tests bestanden wurden.

Bei Funktionen, die nur auf realer TV-Hardware geprüft werden können:

- Debug-APK erzeugen
- erforderliche Diagnose / Logging bereitstellen
- konkreten Gerätetest definieren
- Testergebnis anschließend auswerten und nachbessern

Keine APK als getestet bezeichnen, wenn lediglich kompiliert wurde.

## 28. Entwicklungsphasen

Phase 1 – Launcher MVP:
- Kotlin/Compose-TV-Projekt
- Launcher Activity / Home-App
- installierte Apps
- Basisnavigation
- TV-Focus
- Home-Layout

Phase 2 – Watch Next:
- Android TvProvider
- Watch Next einlesen
- Reihenfolge bewahren
- Fortschritt
- Deep Links

Phase 3 – TMDB:
- API / Resolver / Cache
- Poster / Backdrops / Serien-/Episodendaten
- Detailseite

Phase 4 – Trailer:
- TMDB Video
- YouTube
- Trailerwiedergabe

Phase 5 – Gigablue:
- OpenWebif-Verbindung
- Bouquets / Sender
- EPG Now/Next
- Startseitenreihe

Phase 6 – EPG:
- vollständiger Guide
- TMDB-Anreicherung
- Bilder / Details

Phase 7 – Live-TV:
- Media3
- Gigablue Stream
- Zapping
- Player UI

Phase 8 – weitere Integrationen nur bei Bedarf:
- Kodi
- Jellyfin
- Plex
- CloudStream
- weitere Provider

Vor einer Sonderintegration immer zuerst prüfen, ob Android Watch Next oder Preview Channels bereits genügend Daten liefern.

## 29. Prioritäten

1. zuverlässige Android-TV-Funktion
2. hervorragende D-Pad-/Fernbedienungsbedienung
3. Performance
4. Wartbarkeit
5. saubere Architektur
6. Local First / Datenschutz
7. möglichst geringe Drittanbieterabhängigkeit
8. Optik

## 30. Definition of Done

Eine Funktion ist erst abgeschlossen, wenn:

- sie implementiert ist
- Projekt erfolgreich kompiliert
- relevante Tests erfolgreich sind
- keine offensichtlichen Regressionen vorhanden sind
- TV-Fernbedienungsbedienung soweit möglich geprüft wurde
- Fehlerfälle berücksichtigt wurden
- Code nachvollziehbar strukturiert ist
- GitHub-Änderung dokumentiert ist

## 31. Entscheidungsregel

Bei jeder neuen Anforderung prüfen:

1. Passt sie zum Produktziel?
2. Gibt es bereits eine Android-Standardschnittstelle?
3. Gibt es bereits eine Provider-Schicht, die erweitert werden kann?
4. Muss das gemeinsame Medienmodell erweitert werden?
5. Welche Auswirkungen gibt es auf UI, Cache und Datenbank?
6. Wie lässt sich die Änderung testen?
7. Entsteht unnötige technische Schuld?

Ziel ist kein schneller Wegwerf-Prototyp, sondern ein Launcher, der langfristig täglich als primäre Android-TV-Oberfläche verwendet werden kann.
