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

## Aktueller Stand: Phase 1

Für den MVP bleibt die Gradle-Struktur bewusst klein:

```text
app/
  data/apps/       installierte Apps / PackageManager
  model/           UI-nahe Basismodelle
  ui/home/         Home
  ui/apps/         App-Übersicht
  ui/settings/     Platzhalter für Einstellungen
  ui/theme/        TV-Theme
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

Primärquelle ist Android TvProvider. Die Reihenfolge wird ohne explizite Produktentscheidung nicht verändert. CloudStream-spezifischer Code ist nicht vorgesehen, solange die vorhandenen Watch-Next-Einträge über Android verfügbar sind.

## Gigablue

Direkte OpenWebif-Integration. Keine dreamTV-/TiviMate-Abhängigkeit, sofern OpenWebif die Funktion abdeckt.

## Build-Basis

Phase 1 verwendet einen aktuellen stabilen Android-Toolchain-Stand, der gegen offizielle Android-Dokumentation geprüft wird. Versionen werden explizit gepinnt, nicht dynamisch auf `+` gesetzt.

## Paketkennung

Initiale Application ID: `com.andreassamitsch.ilauncher`.

Sie soll nach den ersten Gerätetests nicht mehr unnötig geändert werden, damit Updates installierbar bleiben.
