# CloudStream Direct-Play Protocol

## Ziel

I Launcher kann einen bereits identifizierten Film, eine Serie oder eine konkrete Episode an einen kompatiblen CloudStream-Build übergeben, ohne CloudStream-Code oder -Extensions in den Launcher einzubetten.

CloudStream bleibt ein eigener Prozess und damit eine klare GPLv3-Grenze. I Launcher bleibt MIT. Über die Grenze gehen ausschließlich Medienidentitäten und Bedienabsicht, niemals aufgelöste Stream-URLs, Tokens oder Provider-Credentials.

## Reproduzierbare CloudStream-Basis

Der Bridge-Build basiert bewusst auf derselben CloudStream-Version wie die zuvor im Projekt verwendete Watch-Next-Test-APK:

- Upstream: `recloudstream/cloudstream`
- Commit: `a72f9e6c3f2e25eb74ce0e7d6cc56dc33c130288`
- Paket: `com.lagradost.cloudstream3.prerelease.debug`
- Version: `4.8.0-PRE`

Der Build reproduziert **vor** der Direct-Play-Bridge den vollständigen, auf TV-Hardware bestätigten Watch-Next-Fix aus der früheren Test-APK:

1. `timeStampHashMap` wird mit `episodeInfo.parentId` gelesen, weil die Map selbst mit `lastWatched.parentId` aufgebaut wird. Dadurch erhält Android TV die echte letzte Engagement-Zeit und nicht einen Fallback-Zeitstempel.
2. `WatchNextProgram.internalProviderId` verwendet denselben stabilen `customId`, der auch für den bestehenden TvProvider-Lookup verwendet wird. Dadurch werden vorhandene Watch-Next-Zeilen aktualisiert statt unabsichtlich neu veröffentlicht.

`tools/cloudstream-bridge/apply_patch.py` enthält harte Guards für beide Invarianten. Fehlt künftig einer der beiden Teile, bricht der Bridge-Build ab.

Die Bridge wird danach auf den exakten Upstream-Commit angewendet und in GitHub Actions mit Unit-Tests und `assemblePrereleaseDebug` gebaut.

## URI

```text
cloudstreamplay://v1?...
```

Unterstützte Parameter:

- `title`
- `originalTitle`
- `year`
- `type=movie|series|episode|unknown`
- `season`
- `episode`
- `episodeTitle`
- `tmdbId`
- `tmdbEpisodeId`
- `imdbId`
- `selection=choose` für die manuelle Providerauswahl

Alle Werte werden UTF-8-codiert.

## Paketauflösung in I Launcher

Wenn mehrere CloudStream-Varianten installiert sind, prüft I Launcher die bekannten Pakete explizit in dieser Reihenfolge:

1. `com.lagradost.cloudstream3.prerelease.debug`
2. `com.lagradost.cloudstream3.prerelease`
3. `com.lagradost.cloudstream3.debug`
4. `com.lagradost.cloudstream3`

Damit gewinnt der Bridge-fähige Development-Build deterministisch vor einer parallel installierten offiziellen CloudStream-App. Die von Android gelieferte Reihenfolge aus `queryIntentActivities()` ist nicht maßgeblich.

## Ladeanzeige

Ein Direct-Play-Handoff kann mehrere Sekunden dauern, weil CloudStream zunächst seine Extension-Runtime bereitstellen, Provider prüfen, Detaildaten laden und anschließend echte Wiedergabelinks auflösen muss.

Deshalb zeigt die Bridge unmittelbar nach dem Handoff einen modalen Ladebildschirm. Er zeigt nur sichere Zustände und niemals Stream-URLs oder Credentials. Typische Phasen sind:

1. `CloudStream wird vorbereitet …`
2. `Provider werden geladen …`
3. `Prüfe <Provider> …`
4. `Stream wird über <Provider> aufgelöst …`
5. `Wiedergabe wird gestartet …`

Bei einer reinen Serienauflösung ohne konkrete Episode lautet die letzte Phase sinngemäß `Öffne <Provider> …`.

Der Ladebildschirm besitzt `Abbrechen`. Nach Abbruch darf kein später fertig werdender Provider-Job mehr Such-Fallback oder Navigation auslösen.

Bei `selection=choose` wird der allgemeine Ladebildschirm geschlossen, sobald die aktiven Provider verfügbar sind; der Providerdialog erscheint dann sofort. Wird dort ein konkreter Provider gewählt, erhält nur dessen anschließende Match-/Linkauflösung wieder eine Ladeanzeige.

## Providerauflösung

CloudStream wartet begrenzt auf die aktive Account-/Extension-Runtime und verwendet ausschließlich die dort aktiven `MainAPI`-Provider.

Automatische Providerreihenfolge:

1. zuletzt tatsächlich abspielbarer Provider für exakt diese Medienidentität
2. global gespeicherte Benutzer-Priorität
3. verbleibende aktive Provider in CloudStreams bestehender Reihenfolge

Provider dürfen mit begrenzter Parallelität anlaufen, werden aber strikt in dieser Prioritätsreihenfolge ausgewertet. Antwortgeschwindigkeit allein verändert die Priorität nicht.

### Identitätsauflösung

Innerhalb eines Providers gilt:

1. Wenn eine IMDb-ID vorhanden ist und der Provider `SyncIdName.Imdb` unterstützt: `getLoadUrl(Imdb, id)` und anschließend `load()`.
2. Sonst Provider-Suche über Titel und optional Originaltitel.
3. Suchergebnisse werden nur grob nach kompatiblem Medientyp gefiltert und nach Titelqualität priorisiert. Ein sichtbarer Provider-Suchtitel darf Zusätze wie Jahr, Sprache oder Edition enthalten und wird deshalb nicht schon vor `load()` als exakter Identitätsbeweis verlangt.
4. Von den bestplatzierten Kandidaten wird nur eine kleine begrenzte Anzahl geladen.
5. Die strenge Prüfung erfolgt auf der echten `LoadResponse`: Medienart, normalisierter Titel/Originaltitel und – sofern auf beiden Seiten vorhanden – ein plausibles Erscheinungsjahr. Ein gleiches angehängtes Jahr wie `(2024)` darf beim Titelvergleich als Dekoration entfernt werden; eine abweichende Jahreszahl bleibt Bestandteil des Vergleichs.

Dadurch werden echte Treffer nicht verworfen, nur weil eine Extension ihren Suchtreffer dekoriert, ohne die eigentliche Detailauflösung unsicher zu machen.

## Wiedergabe

### Film

Nur eine passende `MovieLoadResponse` wird direkt abgespielt. I Launcher erhält keine Links. CloudStream baut einen `ResultEpisode`, verwendet seinen vorhandenen `RepoLinkGenerator` und öffnet den normalen `GeneratorPlayer`.

Vor dem Playerstart wird `RepoLinkGenerator.generateLinks()` als Preflight verwendet. Nur wenn mindestens eine abspielbare Quelle geliefert wird, gilt der Provider als erfolgreich und wird für diese Medienidentität vorgemerkt. Liefert ein Provider trotz richtiger Detailseite keine Wiedergabequelle, wird automatisch der nächste Provider versucht.

### Konkrete Episode

Bei vorhandener Staffel und Folge wird innerhalb einer passenden `TvSeriesLoadResponse` exakt diese Episode gewählt. `seasonNames`/Display-Season-Mapping wird berücksichtigt. Auch hier erfolgt der Link-Preflight vor dem Speichern als letzter erfolgreicher Provider.

### Serie ohne konkrete Episode

Es wird nicht willkürlich Folge 1 gestartet. Nach erfolgreicher Providerauflösung öffnet CloudStream direkt die bereits aufgelöste Serien-Detailseite dieses Providers. Die normale CloudStream-Suchergebnis-Seite wird übersprungen.

## Manuelle Providerauswahl

`selection=choose` öffnet einen TV-bedienbaren Providerdialog **sofort nach Verfügbarkeit der aktiven Extension-Runtime**. Es wird nicht mehr vor dem Öffnen des Dialogs jeder Provider vollständig nach dem aktuellen Titel durchsucht.

Der Dialog zeigt alle für den aktuellen Medientyp grundsätzlich geeigneten aktiven Provider in der wirksamen Reihenfolge. Erst wenn der Benutzer einen Provider auswählt, wird genau dieser Provider für den aktuellen Film bzw. die Episode aufgelöst und auf eine abspielbare Quelle geprüft.

Über `Priorität` lässt sich die globale Reihenfolge der aktiven Provider mit `Nach oben` und `Nach unten` ändern. Neue Provider werden hinten ergänzt; nicht mehr installierte Provider werden ignoriert. Der Prioritätsdialog verwendet bewusst keinen gleichzeitigen `AlertDialog.setMessage()`-Text, weil diese AppCompat-Version sonst die Single-Choice-Liste verdrängt.

## Such-Fallback

Kann kein sicherer Direktmatch ausgeführt werden, bleibt die normale CloudStream-Suche der sichere Fallback.

Wichtig für Kaltstarts: CloudStreams `SearchViewModel` hält eine Momentaufnahme der zu diesem Zeitpunkt geladenen Provider-Repositories. Wird die Suchseite angelegt, bevor Extensions fertig geladen sind, kann ein reines `cloudstreamsearch://` zwar den Titel in das Suchfeld übernehmen, die erste Suche aber gegen eine leere oder unvollständige Repository-Liste laufen.

Die Bridge behebt diesen Fall deshalb aktiv:

1. activity-scoped `SearchViewModel` holen – dieselbe Instanz, die `SearchFragment` verwendet
2. `reloadRepos()` nach der bereits abgewarteten Provider-Runtime
3. `searchAndCancel(title)` explizit starten
4. zusätzlich den bestehenden `cloudstreamsearch://`-Navigationspfad verwenden, damit Suchseite und Suchfeld korrekt geöffnet werden

Damit bedeutet Fallback nicht mehr nur „Suchtext eintragen“, sondern tatsächlich „Suche mit der aktuellen Providerliste ausführen“.

## Watch Next / Weiterschauen

Watch Next bleibt vollständig Eigentum von CloudStream + Android TvProvider. I Launcher liest die veröffentlichte Android-Reihe und besitzt keine CloudStream-spezifische Watch-Next-Datenbank.

CloudStream speichert während der Wiedergabe seine lokale Resume-Position bereits über `DataStoreHelper.setViewPosAndResume(...)`. Im Upstream-Stand `a72f9e6` wird Androids Watch-Next-Reihe jedoch typischerweise erst wieder veröffentlicht, wenn `HomeViewModel.loadResumeWatching()` ausgeführt wird. Dadurch konnte folgende Situation entstehen:

```text
Player verlassen
→ CloudStream-Detailseite
→ lokale Resume-Position korrekt
→ Android TvProvider noch alt
→ erst Rückkehr zur CloudStream-Startseite aktualisiert Watch Next
```

Die Bridge schließt diese Lifecycle-Lücke:

- `GeneratorPlayer.onStop()` persistiert unmittelbar vor dem Player-Stop nochmals die aktuelle Position/Dauer und startet danach eine TvProvider-Aktualisierung.
- `GeneratorPlayer.onDestroy()` besitzt denselben Flush als Safety-Net für alternative Teardown-Pfade.
- Ein kurzer Duplicate-Guard verhindert die normale doppelte Folge `onStop → onDestroy`.
- Für die Veröffentlichung wird `HomeViewModel.getResumeWatching()` wiederverwendet und anschließend `addProgramsToContinueWatching(...)` aufgerufen. Es wird also derselbe CloudStream-Datenpfad verwendet wie auf der Startseite.
- Livestreams und NSFW-Inhalte behalten CloudStreams vorhandene Ausschlusslogik.

Damit sollen insbesondere folgende Fälle aktualisiert werden, ohne dass die CloudStream-Startseite besucht werden muss:

1. Player mit `Zurück` verlassen und auf der Detailseite stehen bleiben.
2. CloudStream direkt aus laufendem Player in den Hintergrund schicken bzw. über Home verlassen.
3. Normale Player-/Fragment-Zerstörung nach Abbruch.

Ein hartes Betriebssystem-`force stop`/Prozess-Kill ohne Lifecycle-Callback kann naturgemäß nicht garantiert synchronisiert werden. Der normale Android-Lifecycle wird dagegen explizit abgedeckt. Weil der TvProvider-Write in einer IO-Coroutine erfolgt, ist auch ein unmittelbar nach `onStop()` vom System getöteter Prozess nicht absolut garantiert; für normale Back/Home/App-Wechsel-Pfade ist der Prozess jedoch weiter aktiv und der Flush kann abgeschlossen werden.

## Diagnose

CloudStream verwendet für die Bridge den Log-Tag `ILauncherBridge`. Geloggt werden nur sichere Zustände wie:

- Medienart / Auswahlmodus
- gekürzter Hash der Medienidentität
- Anzahl aktiver Provider
- Provider `match`, `miss`, `no playable links`
- Direct-Play oder Such-Fallback

Für den Player-Exit-Watch-Next-Flush wird `ILauncherWatchNext` verwendet. Geloggt werden nur Auslöser (`onStop`/`onDestroy`) und Anzahl veröffentlichter Resume-Einträge, keine URLs.

Titel, Provider-URLs, extrahierte Stream-URLs, Tokens und Credentials werden nicht in Bridge-Diagnoselogs geschrieben.

I Launcher protokolliert unter `CLOUDSTREAM_BRIDGE` das gewählte Zielpaket und den Modus `DirectPlay`/`SearchFallback`, ebenfalls ohne Inhalts- oder Stream-URLs.

## Signierung

Die ursprüngliche `CloudStream-WatchNext-Test-a72f9e6.apk` war mit einem nicht erhaltenen ephemeren Android-Debug-Key signiert. Dessen privater Schlüssel kann aus der APK nicht wiederhergestellt werden.

Deshalb musste beim ersten Wechsel auf die reproduzierbare Bridge die alte `com.lagradost.cloudstream3.prerelease.debug` einmal deinstalliert werden. Die neuen Bridge-Builds verwenden anschließend eine feste Development-Signatur und sind untereinander update-kompatibel.

## Hardwarestatus

Automatisierte JVM-Tests und `assemblePrereleaseDebug` prüfen den reproduzierbaren Build. Die tatsächliche Provider-/Extension-Auflösung, Ladeanzeige, D-Pad-Dialoge, Playerstart, Player-Exit-Watch-Next-Aktualisierung und Rückkehr zu I Launcher müssen zusätzlich auf realer TV-Hardware bestätigt werden. Ein erfolgreicher Build allein gilt nicht als Hardwaretest.
