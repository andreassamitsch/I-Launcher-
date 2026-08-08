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
  data/tv/         Android TvProvider / Watch Next / Quellenpräferenzen
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

## Watch Next

Primärquelle ist `TvContract.WatchNextPrograms.CONTENT_URI` des Android TvProvider.

Aktuelle Phase-2-Regeln:

- Query erst nach erteilter `READ_TV_LISTINGS`-Berechtigung
- keine Selection; alle verfügbaren Watch-Next-Zeilen bleiben für Diagnose zugänglich
- Sortierung wird bereits im TvProvider-Query mit `last_engagement_time_utc_millis DESC` angefordert
- `COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS` ist Androids eigener Sortierhinweis für Watch Next; die aktuelle Arc-Launcher-Implementierung verwendet dieselbe DESC-Sortierung
- die so angeforderte Reihenfolge wird 1:1 in `sourceOrder` übernommen und im Mapper nicht verändert
- Benutzer können einzelne Quell-Packages für die Home-Reihe ausblenden; die Filterung entfernt nur Zeilen und sortiert die verbleibenden Einträge nicht neu
- die vollständigen Rohzeilen bleiben unabhängig vom Home-Filter in der Diagnose sichtbar
- relevante Standardfelder werden auf `WatchNextItem` normalisiert
- `package_name` bleibt für Diagnose und Quellenfilter erhalten
- Intent-URI wird nur zum Starten des Inhalts verwendet und nicht vollständig geloggt
- TvProvider-Änderungen werden per `ContentObserver` beobachtet
- Zugriffsfehler werden als Diagnosezustand dargestellt statt die App abstürzen zu lassen
- kein CloudStream-spezifischer Code, solange Android die Einträge liefert

Die Gerätevalidierung vergleicht Anzahl, erste Einträge, Reihenfolge, Fortschritt und Deep-Link-Verhalten mit Arc/Projectivy auf demselben TV.

## Home-Tasten-Fallback

Der Google-TV-/TCL-Home-Fallback basiert auf einem `AccessibilityService`. Die Service-Deklaration ist mit `android.permission.BIND_ACCESSIBILITY_SERVICE` geschützt und fordert Key-Filterung explizit über `canRequestFilterKeyEvents=true` an.

Die eigentliche Benutzerfreigabe ist eine Android-Sonderberechtigung und kann nicht durch einen normalen Runtime-Permission-Dialog erteilt werden. Bei Android 13+ können lokal oder aus einer heruntergeladenen APK installierte Apps zusätzlich unter `Restricted Settings` fallen. In diesem Fall muss der Benutzer in der App-Info über das Drei-Punkte-Menü zuerst „Eingeschränkte Einstellungen zulassen“ freigeben. I Launcher erfasst dafür die vom System gemeldete Installationsquelle und zeigt die passende Einrichtungsführung an; die Sicherheitsfreigabe selbst wird nicht umgangen.

`MainActivity` besitzt getrennte Intent-Filter für HOME, LEANBACK_LAUNCHER und den normalen LAUNCHER-Einstieg. Der reguläre LAUNCHER-Einstieg dient außerdem als Front-Door-Activity für Systemfunktionen wie „Öffnen“ nach einer APK-Installation.

## Bilder

Watch-Next-Quellbilder werden über Coil geladen. Phase 2 verwendet Quellbilder direkt; TMDB-Anreicherung und langfristiges Caching folgen in Phase 3.

## Gigablue

Direkte OpenWebif-Integration. Keine dreamTV-/TiviMate-Abhängigkeit, sofern OpenWebif die Funktion abdeckt.

## Build-Basis

Der aktuelle stabile Android-Toolchain-Stand wird gegen offizielle Dokumentation geprüft. Versionen werden explizit gepinnt, nicht dynamisch auf `+` gesetzt.

## Paketkennung

Application ID: `com.andreassamitsch.ilauncher`.

Sie bleibt stabil, damit Updates installierbar bleiben.
