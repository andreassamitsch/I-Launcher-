# CloudStream Direct-Play Protocol

## Ziel

I Launcher soll einen bereits identifizierten Film, eine Serie oder eine konkrete Episode an unseren separat installierten CloudStream-Build übergeben können, ohne CloudStreams Suche, Trefferliste, Detailseite oder Staffelansicht als unnötige UI-Zwischenschritte öffnen zu müssen.

CloudStream bleibt dabei der Host für Extensions, `MainAPI`-Provider, Extractors und den CloudStream-Player. I Launcher übernimmt keine CloudStream-Providerlogik und erhält keine dauerhaft speicherbaren Stream-URLs.

## Referenzbasis

Die erste Bridge-Version baut reproduzierbar auf dem exakt gleichen CloudStream-Stand wie die frühere Test-APK `CloudStream-WatchNext-Test-a72f9e6.apk` auf:

- Upstream: `recloudstream/cloudstream`
- Commit: `a72f9e6c3f2e25eb74ce0e7d6cc56dc33c130288`
- Paket: `com.lagradost.cloudstream3.prerelease.debug`
- vorhandener Watch-Next-Fix bleibt erhalten: `internalProviderId` wird konsistent mit `id|apiName|url` geschrieben und wiedergefunden.

Der Build wird nicht aus kopiertem GPL-Code im I-Launcher-Prozess erzeugt. Der Workflow checkt den GPL-CloudStream-Quellstand separat aus und wendet die kleine, nachvollziehbare Bridge-Patchschicht aus `tools/cloudstream-bridge/` an.

## Version 1

Die Capability wird über einen normalen Android-`ACTION_VIEW`-Intent entdeckt. Ein kompatibler CloudStream-Build registriert das Scheme:

```text
cloudstreamplay://v1
```

I Launcher prüft die Capability für das tatsächlich installierte CloudStream-Paket per `resolveActivity()`. Ist sie nicht vorhanden, bleibt `cloudstreamsearch://` der automatische Fallback. Wenn parallel mehrere CloudStream-Paketvarianten installiert sind, wird ein Build mit `cloudstreamplay://v1` gegenüber einem reinen Search-Handoff bevorzugt.

### Query-Parameter

| Parameter | Pflicht | Bedeutung |
| --- | --- | --- |
| `title` | ja | normalisierter sichtbarer Titel |
| `originalTitle` | nein | Originaltitel, falls bekannt |
| `year` | nein | Erscheinungs-/Serienjahr als Matching-Signal |
| `type` | ja | `movie`, `series`, `episode` oder `unknown` |
| `season` | nein | Staffelnummer |
| `episode` | nein | Episodennummer |
| `episodeTitle` | nein | Episodentitel |
| `tmdbId` | nein | TMDB-ID des Films bzw. der Serie |
| `tmdbEpisodeId` | nein | TMDB-ID der Episode |
| `imdbId` | nein | IMDb-ID; innerhalb eines Providers bevorzugt für `getLoadUrl()` |
| `selection` | nein | `choose` öffnet die manuelle Auswahl der tatsächlich passenden Provider; fehlt der Wert, läuft der automatische Pfad |

Beispiel automatischer Start:

```text
cloudstreamplay://v1?title=Fallout&type=episode&year=2024&season=2&episode=4&tmdbId=106379&imdbId=tt12637874
```

Beispiel manuelle Providerwahl:

```text
cloudstreamplay://v1?title=Fallout&type=episode&season=2&episode=4&tmdbId=106379&selection=choose
```

Alle Werte werden UTF-8 URL-encoded. Unbekannte optionale Parameter werden weggelassen.

## Provider-Priorität

Provider-/Extension-Namen sind CloudStream-Runtime-Daten und werden deshalb ausschließlich im CloudStream-Prozess gespeichert. I Launcher hält keine zweite Plugin-Liste.

Kurzes OK auf der CloudStream-Aktion verwendet diese Reihenfolge:

1. zuletzt erfolgreich aufgelöster Provider für exakt diese Medienidentität,
2. vom Benutzer festgelegte globale Provider-Reihenfolge,
3. neu hinzugekommene bzw. noch nicht priorisierte aktive Provider in CloudStreams bestehender Reihenfolge.

Für die Inhaltsidentität wird bevorzugt `tmdbEpisodeId`, danach `tmdbId` inklusive Staffel/Folge, danach `imdbId` und erst zuletzt eine normalisierte Titel-/Jahr-Identität verwendet. Es werden nur Provider-Namen gespeichert, keine Stream-URLs oder Tokens.

Die Provider werden mit begrenzter Parallelität gestartet, aber in Prioritätsreihenfolge ausgewertet. Ein langsamer niedriger priorisierter Provider kann damit bereits arbeiten, gewinnt aber nicht gegen einen höher priorisierten gültigen Treffer.

Innerhalb jedes einzelnen Providers gilt weiterhin: IMDb-`getLoadUrl()` zuerst, sofern unterstützt; sonst konservative Titelsuche.

## Manuelle Providerwahl und Priorisierung

Langes OK auf der CloudStream-Aktion sendet `selection=choose`.

- CloudStream ermittelt alle sicheren direkten Treffer der aktiven Provider.
- Angezeigt werden nur Provider, die den konkreten Film bzw. die konkrete Serie/Episode sicher auflösen konnten.
- Auswahl eines Providers startet genau diesen Treffer und merkt ihn als letzten erfolgreichen Provider für diese Medienidentität.
- Im Dialog führt `Priorität` zur globalen Provider-Reihenfolge.
- Dort können aktive Provider per `Nach oben` / `Nach unten` sortiert werden.
- Ist ein priorisierter Provider für einen späteren Inhalt nicht verfügbar oder liefert keinen sicheren Treffer, wird automatisch der nächste versucht.

Wenn bei langer OK-Taste kein sicherer direkter Treffer existiert, kann der Benutzer entweder die normale CloudStream-Suche öffnen oder trotzdem die Provider-Priorität bearbeiten.

## Implementierter CloudStream-Ablauf

1. Der Intent läuft weiterhin durch CloudStreams bestehende `AccountSelectActivity`, damit Account-/PIN-Logik nicht umgangen wird.
2. Die Bridge wartet begrenzt auf die normale Plugin-/Provider-Runtime und berücksichtigt die für den Account aktiven Provider.
3. Provider werden nach Inhalt-Cache und Benutzerpriorität sortiert.
4. Innerhalb eines Providers wird bei vorhandener `imdbId` und `SyncIdName.Imdb` zuerst `getLoadUrl()` versucht.
5. Sonst wird konservativ nach Titel/Originaltitel gesucht. Automatischer Direktstart akzeptiert nur exakte normalisierte Titel-/Originaltitel-Matches und passenden Medientyp; bekannte Jahreswerte dürfen höchstens um ein Jahr abweichen.
6. Der Treffer wird über die vorhandene `APIRepository.load()`-Logik geladen.
7. Film: aus `MovieLoadResponse.dataUrl` wird CloudStreams vorhandener `ResultEpisode`/`RepoLinkGenerator`-Playerpfad aufgebaut und der interne Player direkt geöffnet.
8. Konkrete Episode: bei `TvSeriesLoadResponse` wird exakt die angeforderte Staffel/Folge gewählt und mit `RepoLinkGenerator` direkt gestartet.
9. Serie ohne konkrete Staffel/Folge: die bereits aufgelöste Provider-Detailseite wird geöffnet. Damit entfallen Suche und Trefferliste, aber es wird nicht willkürlich Episode 1 gestartet.
10. `loadLinks()` bleibt im normalen CloudStream-Playerpfad und wird nicht vom I Launcher ausgeführt oder vorzeitig persistiert.
11. Gibt es keinen sicheren Match, fällt die Bridge kontrolliert auf `cloudstreamsearch://<title>` zurück.

## Sicherheit und Stabilität

- Extensions und Extractors laufen ausschließlich im CloudStream-Prozess.
- I Launcher lädt keine CloudStream-Plugin-Dex-Dateien.
- Keine extrahierten Stream-URLs, Session-IDs oder Provider-Credentials in I Launcher persistieren oder loggen.
- Die Protokollversion ist Bestandteil des URI-Hosts. Inkompatible Versionen werden nicht stillschweigend als kompatibel behandelt.
- Watch Next bleibt davon unabhängig und wird weiterhin über Android `TvProvider` gelesen.
- Die Provider-Suche ist parallel begrenzt; bei unsicherem Titel-/Typ-Match wird nicht automatisch der erste Treffer gestartet.

## Signierung der CloudStream-Testlinie

Die frühere `CloudStream-WatchNext-Test-a72f9e6.apk` wurde bei ihrer Erstellung binär gepatcht und anschließend mit einem temporären Android-Debug-Key neu signiert. Der zugehörige private Schlüssel wurde nicht dauerhaft hinterlegt und lässt sich aus der APK nicht rekonstruieren.

Die neue Bridge-Testlinie wird daher mit der vorhandenen stabilen Projekt-Development-Signatur gebaut. Ein einmaliger Wechsel von der früheren Watch-Next-Test-APK auf die neue Bridge-APK kann deshalb eine Deinstallation der alten `com.lagradost.cloudstream3.prerelease.debug`-Installation erfordern. Danach bleiben weitere Bridge-Builds unter derselben stabilen Signatur updatefähig.

## UI-Verhalten in I Launcher

Wenn `cloudstreamplay://v1` vom installierten CloudStream-Paket aufgelöst werden kann, verwendet die CloudStream-Aktion auf der Detailseite den Direct-Handoff. Andernfalls verwendet dieselbe Aktion weiterhin `cloudstreamsearch://`.

Kurzes OK startet automatisch nach Cache/Priorität. Langes OK öffnet die Provider-Auswahl. Film und konkrete Episode können direkt in den CloudStream-Player springen. Eine reine Serienidentität ohne Staffel/Folge wird bis zur Einführung eines Staffel-/Episodenbrowsers in I Launcher direkt auf die bereits gefundene CloudStream-Serienseite geführt.
