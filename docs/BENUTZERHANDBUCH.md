# GM Medic – Guide für neue User

> Willkommen bei der **GM Medic** Mod! Diese Anleitung erklärt dir Schritt für
> Schritt, **was die Mod kann** und **wie du sie im Alltag als Sanitäter / EMS auf
> GermanMiner nutzt**. Es geht hier nur um die **Funktionen** – nicht um Einbau,
> Technik oder die Server-API.

<!--
📸 SCREENSHOT-VORLAGE (bitte hier ein Titelbild einfügen):
Ein Ingame-Bild, auf dem die Mod im Einsatz zu sehen ist –
z. B. das HUD mit ein paar aktiven Notrufen. Wirkt als Blickfang oben im Guide.

![GM Medic im Einsatz](bilder/titelbild.png)
-->

---

## 📚 Inhalt

1. [Was macht die Mod?](#1-was-macht-die-mod)
2. [Das Notruf-HUD](#2-das-notruf-hud)
3. [Spieler-Markierung (Highlight)](#3-spieler-markierung-highlight)
4. [Fahrzeug-Automatik](#4-fahrzeug-automatik)
5. [Schnellstart – die wichtigsten Schritte](#5-schnellstart--die-wichtigsten-schritte)
6. [Alle Befehle auf einen Blick](#6-alle-befehle-auf-einen-blick)
7. [Häufige Fragen (FAQ)](#7-häufige-fragen-faq)

---

## 1. Was macht die Mod?

GM Medic ist dein **digitaler Assistent im EMS-Dienst**. Die Mod liest automatisch
den Funk (`[FUNK]`) und die System-Nachrichten des Servers mit und nimmt dir die
lästigen Handgriffe ab, damit du dich auf den Einsatz konzentrieren kannst.

Sie erledigt für dich vor allem:

- 🚑 **Notrufe & Todesmeldungen anzeigen** – übersichtlich auf dem Bildschirm.
- 🎯 **Betroffene Spieler markieren** – du siehst sofort, wo jemand Hilfe braucht.
- 🚗 **Fahrzeug-Kram automatisieren** – Motor, Gang, Sirene, Aussteigen.
- 🟢 **Dienststatus erkennen** – die Mod weiß automatisch, ob du im Dienst bist.

> **Wichtig:** Du musst nichts von Hand eintragen. Sobald ein Notruf im Funk
> auftaucht, erscheint er automatisch bei dir.

<!--
📸 SCREENSHOT: Übersicht / Gesamteindruck der Mod im Spiel.
![Gesamtüberblick](bilder/uebersicht.png)
-->

---

## 2. Das Notruf-HUD

Das **HUD** ist die Liste am Bildschirmrand, die dir alle **aktiven Notrufe**
(*Notrufe*) und **Todesmeldungen** (*Todesmeldungen*) anzeigt.

Zu jedem Eintrag siehst du:

- **Wer** den Notruf ausgelöst hat (Anrufer)
- **Warum** (Grund des Notrufs)
- **Status** – ob der Ruf schon jemandem zugewiesen ist

Die Einträge aktualisieren sich automatisch: Wird ein Ruf **angenommen,
abgelehnt, erreicht oder zurückgezogen**, ändert sich die Anzeige von selbst.

<!--
📸 SCREENSHOT: Das HUD mit mehreren aktiven Notrufen.
Am besten so, dass man Anrufer, Grund und Status gut erkennt.
![Notruf-HUD](bilder/hud.png)
-->

### Kompakt-Modus

Ist dir das HUD zu groß (z. B. bei hoher GUI-Skalierung), kannst du eine
**kompakte, kleinere Darstellung** einschalten:

```
/gmhud compact
```

<!--
📸 SCREENSHOT: Vergleich normal vs. kompakt (gerne zwei Bilder nebeneinander).
![HUD kompakt](bilder/hud-kompakt.png)
-->

### HUD-Einstellungen prüfen

Willst du wissen, was aktuell eingestellt ist:

```
/gmhud status
```

---

## 3. Spieler-Markierung (Highlight)

Damit du die betroffenen Spieler in der Welt **nicht mühsam suchen** musst, kann
die Mod eine **farbige Box** um jeden Spieler zeichnen, der einen offenen Ruf hat.

- 🔴 **Rot** = Todesmeldung
- 🟠 **Orange** = Notruf

Die Markierung ist **nur im Dienst aktiv** und funktioniert bis zu einer
einstellbaren Entfernung.

**Ein-/Ausschalten:**

```
/gmhud highlight
```

**Reichweite festlegen** (in Blöcken, 8–256):

```
/gmhud highlight range 128
```

<!--
📸 SCREENSHOT: Ein Spieler mit farbiger Markierungs-Box in der Welt.
Am besten einmal Rot (Tod) und einmal Orange (Notruf).
![Spieler-Highlight](bilder/highlight.png)
-->

---

## 4. Fahrzeug-Automatik

Sobald du in ein **EMS-Fahrzeug** einsteigst, kann die Mod die typischen
Handgriffe automatisch für dich erledigen. Jede Funktion lässt sich **einzeln
ein- und ausschalten**.

| Funktion | Was passiert |
|----------|--------------|
| **Motor** | Startet den Motor automatisch beim Einsteigen. |
| **Gang** | Setzt den Gangschalter automatisch. |
| **Sirene** | Schaltet die Sirene ein, solange du einen angenommenen Ruf hast. |
| **Aussteigen** | Schaltet beim Rausschleichen erst die Sirene aus und steigt dann kurz verzögert aus. |

> 🚁 **Hinweis:** Helikopter werden automatisch erkannt und von der
> Aussteige-Automatik ausgenommen.

**Beispiele:**

```
/gmvehicle motor on        # Motor-Automatik einschalten
/gmvehicle siren on        # Sirene-Automatik einschalten
/gmvehicle exitdelay 20    # Aussteige-Verzögerung (20 Ticks = 1 Sekunde)
/gmvehicle status          # Aktuelle Einstellungen ansehen
```

Möchtest du, dass eine Funktion **immer** läuft – auch wenn du **nicht im Dienst**
bist – hängst du `always` an:

```
/gmvehicle motor always on
```

<!--
📸 SCREENSHOT: Einstieg in ein EMS-Fahrzeug mit eingeschalteter Sirene.
![Fahrzeug-Automatik](bilder/fahrzeug.png)
-->

---

## 5. Schnellstart – die wichtigsten Schritte

Für den Einstieg reicht das hier völlig aus:

1. **In den Dienst gehen** – ganz normal über den Server. Die Mod erkennt das
   automatisch (grüner Dienststatus).
2. **Status prüfen** mit:
   ```
   /gmstatus
   ```
   → zeigt dir, ob du im Dienst bist und wie viele Rufe aktiv sind.
3. **Highlight aktivieren**, damit du Betroffene sofort siehst:
   ```
   /gmhud highlight
   ```
4. **Fahrzeug-Automatik einschalten**, was du magst:
   ```
   /gmvehicle motor on
   /gmvehicle siren on
   ```
5. **Losfahren und helfen!** Neue Notrufe erscheinen automatisch im HUD.

<!--
📸 SCREENSHOT: Der /gmstatus-Befehl im Chat mit Dienststatus.
![Statusabfrage](bilder/status.png)
-->

---

## 6. Alle Befehle auf einen Blick

### 🟢 Status

| Befehl | Beschreibung |
|--------|--------------|
| `/gmstatus` | Schnellüberblick: Dienststatus und Anzahl aktiver Rufe. |

### 🖥️ HUD – `/gmhud`

| Befehl | Beschreibung |
|--------|--------------|
| `/gmhud compact` | Kompakte (kleinere) HUD-Darstellung ein-/ausschalten. |
| `/gmhud highlight` | Markierung von Spielern mit offenem Ruf ein-/ausschalten. |
| `/gmhud highlight range <8-256>` | Reichweite der Markierung in Blöcken. |
| `/gmhud status` | Aktuelle HUD-Einstellungen anzeigen. |

### 🚗 Fahrzeug-Automatik – `/gmvehicle`

| Befehl | Beschreibung |
|--------|--------------|
| `/gmvehicle motor on\|off` | Motor beim Einsteigen automatisch starten. |
| `/gmvehicle gear on\|off` | Gangschalter automatisch setzen. |
| `/gmvehicle siren on\|off` | Sirene automatisch schalten, wenn du einen Ruf hast. |
| `/gmvehicle <funktion> always on\|off` | Funktion auch außer Dienst aktiv halten. |
| `/gmvehicle exitdelay <1-200>` | Aussteige-Verzögerung in Ticks (20 Ticks = 1 s). |
| `/gmvehicle status` | Aktuelle Fahrzeug-Einstellungen anzeigen. |

---

## 7. Häufige Fragen (FAQ)

**❓ Muss ich Notrufe von Hand eintragen?**
Nein. Die Mod liest den Funk automatisch mit – Rufe erscheinen von selbst.

**❓ Warum sehe ich keine Markierungen um Spieler?**
Die Markierung ist nur **im Dienst** aktiv. Prüfe mit `/gmstatus`, ob du im
Dienst bist, und schalte das Highlight mit `/gmhud highlight` ein. Achte auch auf
die eingestellte Reichweite (`/gmhud highlight range`).

**❓ Das HUD ist mir zu groß.**
Schalte mit `/gmhud compact` die kleinere Ansicht ein.

**❓ Passiert bei Helikoptern auch die Aussteige-Automatik?**
Nein, Helikopter werden erkannt und dabei ausgenommen.

**❓ Was passiert, wenn ich den Server verlasse?**
Der Dienststatus wird automatisch zurückgesetzt.

<!--
📸 SCREENSHOT (optional): Ein Abschluss-Bild, z. B. ein erfolgreicher Einsatz.
![Einsatz erfolgreich](bilder/abschluss.png)
-->

---

> 💡 **Tipp:** Lege deine Screenshots am besten in einen Ordner `docs/bilder/`
> und ersetze die Beispiel-Pfade oben (`bilder/…png`) durch deine eigenen
> Dateinamen.
