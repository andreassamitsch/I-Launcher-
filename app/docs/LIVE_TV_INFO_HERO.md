# Live-TV-Sendungsinfo als Hero

Stand: 19.09.2026

Im Live-TV-Overlay steht **Info** vor **EPG**. Nach Info erscheint statt des bisherigen gerahmten Dialogs ein bildschirmfüllender Sendungs-Hero im Stil der I-Launcher-Startseite: großes Artwork rechts, Verlauf nach links/unten, Sendungsdaten und Beschreibung links. Die laufende SAT-/Joyn-Wiedergabe wird nicht angehalten. Während der Hero sichtbar ist, wird die kleine Kanalinfo links oben ausgeblendet.

**Info schließen** oder Zurück führt zur Senderübersicht zurück. Die Info-Taste auf der Fernbedienung schließt den Hero ebenfalls; CH+/CH− schaltet den Sender und beendet die Infoansicht. Der Senderwechsel stellt die reguläre Kanalinfo wieder her. Das bisherige EPG und die Sender-/SAT-/Joyn-Steuerung bleiben unverändert.

Relevante Dateien: `LiveTvPlayerScreen.kt`, `LiveTvProgramHero.kt`.
