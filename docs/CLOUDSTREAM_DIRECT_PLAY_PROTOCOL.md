# CloudStream Direct-Play Protocol

## Ziel

I Launcher soll einen bereits identifizierten Film, eine Serie oder eine konkrete Episode an unseren separat installierten CloudStream-Fork übergeben können, ohne CloudStreams Suche, Trefferliste, Detailseite oder Staffelansicht als UI-Zwischenschritte öffnen zu müssen.

CloudStream bleibt dabei der Host für Extensions, `MainAPI`-Provider, Extractors und den CloudStream-Player. I Launcher übernimmt keine CloudStream-Providerlogik und erhält keine dauerhaft speicherbaren Stream-URLs.

## Version 1

Die Capability wird über einen normalen Android-`ACTION_VIEW`-Intent entdeckt. Ein kompatibler CloudStream-Fork registriert das Scheme:

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

## Erwartetes Verhalten im CloudStream-Fork

1. Account-/Plugin-Runtime wie beim normalen App-Start initialisieren.
2. Wenn `imdbId` vorhanden ist, Provider mit passender `supportedSyncNames`-/`getLoadUrl()`-Unterstützung zuerst versuchen.
3. Sonst bzw. bei keinem sicheren Sync-ID-Treffer die vorhandenen Provider-Suchfunktionen mit begrenzter Parallelität verwenden.
4. Treffer konservativ gegen Titel/Originaltitel, Jahr und Medientyp prüfen. Bei unklarem Match nicht automatisch den ersten Treffer starten.
5. Den sicheren Treffer über die bestehende `APIRepository.load()`-Logik laden.
6. Bei Episoden exakt Staffel und Episodennummer auswählen.
7. Wiedergabe über CloudStreams vorhandenen `RepoLinkGenerator`/Player starten. `loadLinks()` erst beim tatsächlichen Playback ausführen.
8. Wenn kein sicherer Direktpfad möglich ist, kontrolliert auf CloudStreams normale Suche für denselben Titel zurückfallen.

## Sicherheit und Stabilität

- Extensions und Extractors laufen ausschließlich im CloudStream-Prozess.
- I Launcher lädt keine CloudStream-Plugin-Dex-Dateien.
- Keine extrahierten Stream-URLs, Session-IDs oder Provider-Credentials in I Launcher persistieren oder loggen.
- Die Protokollversion ist Bestandteil des URI-Hosts. Inkompatible Versionen dürfen nicht stillschweigend als kompatibel behandelt werden.
- Watch Next bleibt davon unabhängig und wird weiterhin über Android `TvProvider` gelesen.

## UI-Verhalten in I Launcher

Wenn `cloudstreamplay://v1` vom installierten CloudStream-Paket aufgelöst werden kann, zeigt die CloudStream-Aktion auf der Detailseite ein Play-Symbol und startet den Direct-Play-Intent. Andernfalls zeigt dieselbe Aktion weiterhin das Suchsymbol und verwendet `cloudstreamsearch://`.
