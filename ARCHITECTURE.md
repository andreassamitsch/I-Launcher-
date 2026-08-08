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
  system/          Home-Rolle / Accessibility / TvProvider-Berechtigungen
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

## Android TvProvider Berechtigung

Für das Lesen von TV-Daten anderer Apps verwendet I Launcher `android.permission.READ_TV_LISTINGS`.

Diese Berechtigung ist die gemeinsame Basis für:

- Android Watch Next / `WatchNextPrograms`
- Preview Channels / `Channels`
- Preview Programs / `PreviewPrograms`

Sie wird im Manifest deklariert und zur Laufzeit angefordert. Fehlt die Freigabe, begrenzt der Android TvProvider normale Abfragen auf Daten der aufrufenden App; I Launcher behandelt diesen Zustand deshalb explizit als fehlende Berechtigung und nicht als leere Inhaltsquelle.

`com.android.providers.tv.permission.READ_EPG_DATA` bleibt als kompatible Legacy-Deklaration enthalten, ist in AOSP aber nicht mehr die maßgebliche Berechtigung für den Zugriff auf fremde TV-Listings.

Der Permission-Flow ist im signierten Development-Build `0.1.0-dev.18` (`26000018`) build-validiert; die tatsächliche Freigabe und Datenlieferung muss auf dem TCL-Gerät geprüft werden.

## Watch Next

Primärquelle ist `TvContract.WatchNextPrograms.CONTENT_URI` des Android TvProvider.

Aktuelle Phase-2-Regeln:

- Query erst nach erteilter `READ_TV_LISTINGS`-Berechtigung
- Query ohne Selection und ohne eigene Sortierung
- Cursor-Reihenfolge wird 1:1 in `sourceOrder` übernommen
- relevante Standardfelder werden auf `WatchNextItem` normalisiert
- `package_name` bleibt für Diagnose und spätere Quellanzeige erhalten
- Intent-URI wird nur zum Starten des Inhalts verwendet und nicht vollständig geloggt
- TvProvider-Änderungen werden per `ContentObserver` beobachtet
- Zugriffsfehler werden als Diagnosezustand dargestellt statt die App abstürzen zu lassen
- kein CloudStream-spezifischer Code, solange Android die Einträge liefert

Die Gerätevalidierung vergleicht Anzahl, Reihenfolge, Fortschritt und Deep-Link-Verhalten mit Arc Launcher auf demselben TV.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService`. Die Service-Deklaration ist mit `android.permission.BIND_ACCESSIBILITY_SERVICE` geschützt. Die eigentliche Benutzerfreigabe ist eine Android-Sonderberechtigung und kann nicht durch einen normalen Runtime-Permission-Dialog erteilt werden; I Launcher führt deshalb gezielt in die Bedienungshilfen und zeigt den Aktivierungsstatus in den Einstellungen.

## Bilder

Watch-Next-Quellbilder werden über Coil geladen. Phase 2 verwendet Quellbilder direkt; TMDB-Anreicherung und langfristiges Caching folgen in Phase 3.

## Gigablue

Direkte OpenWebif-Integration. Keine dreamTV-/TiviMate-Abhängigkeit, sofern OpenWebif die Funktion abdeckt.

## Build-Basis

Der aktuelle stabile Android-Toolchain-Stand wird gegen offizielle Dokumentation geprüft. Versionen werden explizit gepinnt, nicht dynamisch auf `+` gesetzt.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
