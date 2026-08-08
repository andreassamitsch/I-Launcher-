# I Launcher Architecture

`AGENTS.md` ist verbindlich. Dieses Dokument beschreibt die aktuelle technische Zielarchitektur und wird mit dem Projekt weiterentwickelt.

## Architekturprinzipien

- Content-first statt App-first
- Local First
- Datenquellen hinter Provider-/Repository-Grenzen
- UI kennt möglichst keine externen API-Details
- D-Pad und Focus als Kernanforderung, nicht als nachträgliche Anpassung
- lokale Daten zuerst, Netzwerkupdates danach
- keine app-spezifischen Integrationen, wenn Android-Standardschnittstellen ausreichen

## Aktueller Stand: Phase 2

Die Gradle-Struktur bleibt vorerst bewusst bei einem `app`-Modul. Die Provider-Grenzen werden aber bereits im Package-Aufbau getrennt:

```text
app/
  data/apps/       installierte Apps / PackageManager
  data/tv/         Android TvProvider / Watch Next
  data/update/     Development-Updatekanal
  model/           UI-unabhängige Basismodelle
  system/          Home-Rolle / Accessibility-Fallback
  ui/home/         Home inklusive Watch-Next-Reihe
  ui/apps/         App-Übersicht
  ui/settings/     Einstellungen und Diagnose
  ui/components/   TV-Cards
  ui/theme/        TV-Material-Theme
```

Die logischen Grenzen sind so gewählt, dass spätere Gradle-Module ohne kompletten Umbau möglich bleiben.

## Zielarchitektur

```text
app
core:model
core:database
core:network
core:ui
core:navigation
provider:androidtv
provider:tmdb
provider:youtube
provider:enigma2
feature:home
feature:watchnext
feature:details
feature:livetv
feature:epg
feature:apps
feature:search
feature:settings
playback
```

Die tatsächliche Modularisierung erfolgt schrittweise, wenn die Komplexität sie rechtfertigt.

## Datenfluss

```text
Android TvProvider ─┐
Installed Apps ─────┤
TMDB ───────────────┤
OpenWebif ──────────┤
Optional Provider ──┘
        ↓
Repositories / Resolver
        ↓
Unified Models + Room Cache
        ↓
ViewModels / StateFlows
        ↓
Compose for TV UI
```

## Watch Next

Primärquelle ist `TvContract.WatchNextPrograms.CONTENT_URI` des Android TvProvider.

Aktuelle Phase-2-Regeln:

- Query ohne Selection und ohne eigene Sortierung
- Cursor-Reihenfolge wird 1:1 in `sourceOrder` übernommen
- relevante Standardfelder werden auf `WatchNextItem` normalisiert
- `package_name` bleibt für Diagnose und spätere Quellanzeige erhalten
- Intent-URI wird nur zum Starten des Inhalts verwendet und nicht vollständig geloggt
- TvProvider-Änderungen werden per `ContentObserver` beobachtet
- Zugriffsfehler werden als Diagnosezustand dargestellt statt die App abstürzen zu lassen
- kein CloudStream-spezifischer Code, solange Android die Einträge liefert

Die erste Gerätevalidierung vergleicht Anzahl, Reihenfolge, Fortschritt und Deep-Link-Verhalten mit Arc Launcher auf demselben TV.

## Bilder

Watch-Next-Quellbilder werden über Coil geladen. Phase 2 verwendet Quellbilder direkt; TMDB-Anreicherung und langfristiges Caching folgen in Phase 3.

## Gigablue

Direkte OpenWebif-Integration. Keine dreamTV-/TiviMate-Abhängigkeit, sofern OpenWebif die Funktion abdeckt.

## Build-Basis

Der aktuelle stabile Android-Toolchain-Stand wird gegen offizielle Dokumentation geprüft. Versionen werden explizit gepinnt, nicht dynamisch auf `+` gesetzt.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
