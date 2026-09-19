# Live-TV-Sendungsinfo als Hero

Stand: 19.09.2026

**OK → Info** öffnet den großen, an die I-Launcher-Übersicht angelehnten Sendungs-Hero. Das Sendungsbild mit seitlichem Verlauf und die Beschreibung stehen oberhalb der weiter sichtbaren Senderreihe. Die Überschrift **„Jetzt im TV“** und die darunterliegende Bedienknopfleiste werden während Info ausgeblendet. Die kleine Kanalinfo links oben verschwindet ebenfalls. Die SAT-/Joyn-Wiedergabe läuft weiter.

**Fokus statt Umschalten:** Mit D-Pad links/rechts bewegt man sich durch die Senderkacheln und die Hero-Sendungsinfo folgt dem gerade fokussierten Sender. Erst mit OK auf eine Kachel wird tatsächlich umgeschaltet; damit endet der Info-Modus und die normale Kanalinfo erscheint wieder. Die EPG-Anreicherung folgt dem fokussierten Sender. Über „Info schließen“, Zurück oder die Info-Taste gelangt man zur normalen Senderübersicht. CH+/CH− schaltet weiterhin direkt den Sender.

**Lange Beschreibung:** Innerhalb des begrenzten Textfensters wird nur bei Überlänge nach einer kurzen Lesepause sanft vertikal gescrollt; nach einer Pause am Ende beginnt der Text wieder oben. Bei Fokus-/Sendungswechsel startet der Text oben neu. Der vollständige Text wird nicht mehr nach sechs Zeilen abgeschnitten.

**Details zu Serien:** Der aus Live-TV übergebene EPG-Eintrag hat bei bekannter TMDB-Serie/Staffel/Folge Vorrang vor einer gespeicherten Serien-Fortsetzung. Die zugehörige Staffel wird geladen, die konkrete Episode in der Episodenreihe hervorgehoben und nach Möglichkeit fokussiert; auch der direkte CloudStream-Handoff verwendet diese EPG-Episode statt der bisherigen Serien-Fortsetzung. Bei fehlenden EPG-Staffel- oder Episodennummern wird keine Folge geraten; der normale Serien-Standard bleibt erhalten.

Relevante Dateien: `ui/livetv/LiveTvPlayerScreen.kt`, `ui/livetv/LiveTvProgramHero.kt`, `ui/details/DetailsScreen.kt`, `ui/LauncherApp.kt`.
