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

I Launcher prüft die Capability für das tatsächlich installierte CloudStream-Paket per `resolveActivity()`. Ist sie nicht vorhanden, bleibt `cloudstreamsearch://` der automatische Fallback.

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
| `imdbId` | nein | IMDb-ID, bevorzugte direkte Sync-ID für unterstützende CloudStream-Provider |

Beispiel:

```text
cloudstreamplay://v1?title=Fallout&type=episode&year=2024&season=2&episode=4&tmdbId=106379&imdbId=tt12637874
```

Alle Werte werden UTF-8 URL-encoded. Unbekannte optionale Parameter werden weggelassen.

## Implementierter CloudStream-Ablauf

1. Der Intent läuft weiterhin durch CloudStreams bestehende `AccountSelectActivity`, damit Account-/PIN-Logik nicht umgangen wird.
2. Die Bridge wartet begrenzt auf die normale Plugin-/Provider-Runtime und berücksichtigt die für den Account aktiven Provider.
3. Wenn `imdbId` vorhanden ist, werden Provider mit `SyncIdName.Imdb`/`getLoadUrl()` zuerst versucht.
4. Sonst werden Provider mit begrenzter Parallelität durchsucht. Automatischer Direktstart akzeptiert nur konservative exakte normalisierte Titel-/Originaltitel-Matches und passenden Medientyp; bekannte Jahreswerte dürfen höchstens um ein Jahr abweichen.
5. Der Treffer wird über die vorhandene `APIRepository.load()`-Logik geladen.
6. Film: aus `MovieLoadResponse.dataUrl` wird CloudStreams vorhandener `ResultEpisode`/`RepoLinkGenerator`-Playerpfad aufgebaut und der interne Player direkt geöffnet.
7. Konkrete Episode: bei `TvSeriesLoadResponse` wird exakt die angeforderte Staffel/Folge gewählt und mit `RepoLinkGenerator` direkt gestartet.
8. Serie ohne konkrete Staffel/Folge: die bereits aufgelöste Provider-Detailseite wird geöffnet. Damit entfallen Suche und Trefferliste, aber es wird nicht willkürlich Episode 1 gestartet.
9. `loadLinks()` bleibt im normalen CloudStream-Playerpfad und wird nicht vom I Launcher ausgeführt oder vorzeitig persistiert.
10. Gibt es keinen sicheren Match, fällt die Bridge kontrolliert auf `cloudstreamsearch://<title>` zurück.

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

Film und konkrete Episode können direkt in den CloudStream-Player springen. Eine reine Serienidentität ohne Staffel/Folge wird bis zur Einführung eines Staffel-/Episodenbrowsers in I Launcher direkt auf die bereits gefundene CloudStream-Serienseite geführt.
