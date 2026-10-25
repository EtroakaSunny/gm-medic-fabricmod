# GM Medic – Benutzerhandbuch

GM Medic ist ein **rein clientseitiges** Fabric-Mod für den Medic-/Rettungsdienst-Rollenspiel
auf GermanMiner. Es liest den Server-Funk (`[FUNK]`) und Systemnachrichten automatisch mit,
zeigt deine aktiven Notrufe auf einem HUD an, hebt hilfsbedürftige Spieler hervor und übernimmt
die lästigen Handgriffe im Rettungswagen, damit du dich auf den Einsatz konzentrieren kannst.

> Dieses Handbuch beschreibt die Spielerperspektive. Technische Details (Branches, Build-Optionen,
> Entwickler-Befehle) stehen weiterhin in der [README](../../README.md) im jeweiligen Branch.

---

## Inhalt

1. [Voraussetzungen & Installation](#voraussetzungen--installation)
2. [Erste Schritte](#erste-schritte)
3. [Funktionen im Detail](#funktionen-im-detail)
4. [Einstellungsmenü (/gmmenu)](#einstellungsmenü-gmmenu)
5. [Befehle](#befehle)
6. [Welche Version brauche ich?](#welche-version-brauche-ich)
7. [Fehlerbehebung / FAQ](#fehlerbehebung--faq)

---

## Voraussetzungen & Installation

- **Fabric Loader** und **Fabric API** (passend zu deiner Minecraft-Version)
- **Java 21+** (stabile 1.21.x-Versionen) bzw. **Java 25+** (experimentelle Alpha-Versionen,
  siehe [Welche Version brauche ich?](#welche-version-brauche-ich))
- Die GM-Medic-`.jar` für **genau deine Minecraft-Version** — der Dateiname enthält die
  Minecraft-Version, z. B. `gm-medic-0.1.2-Beta+1.21.11.jar`

**Installation:**

1. Fabric Loader für deine Minecraft-Version installieren.
2. Passende Fabric-API-Version in den `mods`-Ordner legen.
3. Die zu deiner Minecraft-Version passende GM-Medic-`.jar` ebenfalls in den `mods`-Ordner legen.
4. Minecraft mit dem Fabric-Profil starten.

Das Mod ist rein clientseitig — auf dem Server muss nichts installiert werden.

---

## Erste Schritte

1. GermanMiner beitreten. Sobald die Serveradresse `germanminer.de` enthält, verbindet sich das
   Mod automatisch im Hintergrund mit dem GM-Medic-API-Server (siehe
   [Server-API-Sync](#server-api-sync)). Das läuft ohne dein Zutun, egal ob du im Dienst bist
   oder nicht.
2. Bist du auf dem Medic-Fraktions-Roster gelistet, wirst du automatisch freigeschaltet — keine
   manuelle Anmeldung nötig.
3. Dienst starten (im Spiel über die üblichen Serverbefehle) — das Mod erkennt das automatisch
   am Chat/Funk und aktiviert ab dann HUD, Highlighting und Fahrzeug-Automatik.
4. `/gmmenu` öffnen, um HUD, Fahrzeug-Automatik und Auto-Antwort nach deinem Geschmack
   einzustellen (siehe [Einstellungsmenü](#einstellungsmenü-gmmenu)).

---

## Funktionen im Detail

### Notruf-HUD

Eine Liste aller aktiven Notrufe und Todesmeldungen auf dem Bildschirm — mit Anrufer, Grund und
Zuweisungsstatus. Für große GUI-Skalierungen gibt es einen **kompakten Modus**
(`Kompakter Modus` im HUD-Tab).

### Automatische Chat-Erkennung

Das Mod liest `[FUNK]` und Systemnachrichten mit und erkennt automatisch:

- Dienstantritt / Dienstende
- neue Notrufe und Todesmeldungen
- angenommene, abgelehnte, erreichte oder zurückgezogene Rufe

Kein manuelles Eintragen nötig — das Mod merkt sich außerdem deinen Dienststatus und setzt ihn
beim Verbindungsabbruch automatisch zurück.

### Ziel-Highlighting

Spieler mit offenem Ruf werden mit einer Umriss-Box markiert, damit du sie in der Welt sofort
findest. Nur aktiv **im Dienst**, in konfigurierbarer Reichweite (`Highlight-Reichweite`),
farblich unterschieden nach Ruftyp: **Todesmeldung = rot**, **Notruf = orange**.

### Schlüsselwort-Highlighting

Schreibt jemand `heal`, `heilung`, `leben` oder `low` im öffentlichen Chat, wird der Spieler für
30 Sekunden hervorgehoben — auch ohne offiziellen Ruf. Die Markierung verschwindet automatisch,
sobald ein Medic dem Spieler einen Verband anlegt.

### Fahrzeug-Automatik

Beim Einsteigen in ein Rettungsfahrzeug kann das Mod automatisch:

- **Motor** starten (`/vehicles motor`)
- **Gangschaltung** setzen (legt das vom Server ausgegebene Gang-Item ab)
- **Sirene** einschalten, solange ein angenommener Ruf aktiv ist
- Beim **Aussteigen (Schleichen)** zuerst die Sirene ausschalten und den Ausstieg kurz verzögern
  (Verzögerung einstellbar); Hubschrauber werden erkannt und ausgenommen

Jede Teilfunktion ist einzeln umschaltbar und kann auf „immer aktiv" gestellt werden (auch außer
Dienst) — im Fahrzeug-Automatik-Tab des Einstellungsmenüs.

### Blutspende-System

Verfolgt Blutspende-Cooldowns und zeigt sie am anvisierten Spieler an (Spritzen-Status,
Highlight-Box mit Restzeit-Label). Zwei getrennte Schalter im HUD-Tab:

- **Blutspende-Anzeige** — die Gesamtfunktion (Master-Schalter). Ist sie aus, verschwinden auch
  Chat-Meldung, Anzeige und Label vollständig.
- **Blutspende-Meldung** — blendet nur die Chat-Zeile aus, die *fremde* Spenden anderer Medics
  erzeugen. Cooldown-Tracking, Anzeige und die eigene Meldung an den API-Server laufen unabhängig
  davon weiter.

### Bankalarm (D-Funk)

Meldet der D-Funk (Ⓓ-Kanal) `Der Alarm der <Bank> wurde ausgelöst`, wird der Alarm an den
API-Server gemeldet und an **alle nicht im Dienst befindlichen** Clients weitergeleitet: Titel,
Signalhorn-Sound, Chat-Zeile und ein blinkendes Banner über die volle Bildschirmbreite — bis der
D-Funk `Der Bankraub wurde beendet.` meldet (Sicherheits-Timeout nach 30 Minuten, falls diese
Zeile verpasst wird). Medics im Dienst hören den D-Funk ohnehin selbst mit und bekommen daher
keinen zusätzlichen Alarm.

### Wiederbelebungs-Autoantwort

Optional: direkt nach deiner eigenen „Ich habe X wiederbelebt!"-Nachricht schickt das Mod
automatisch eine öffentliche Antwort — **nur**, wenn du selbst wiederbelebt/verbunden hast, nie
bei fremden Wiederbelebungen, die du nur im Chat siehst. Schalter und Antworttext lassen sich im
Einstellungsmenü anpassen (`{player}` wird durch den Namen des wiederbelebten Spielers ersetzt).

### Server-API-Sync

Verbindet sich per WebSocket mit dem GM-Medic-API-Server und synchronisiert:

- Dienststatus
- Rufe (neu / zugewiesen / erledigt / abgelehnt)
- regelmäßige Standort-Updates (nur im Dienst) und Keep-Alive
- „Nächster freier Medic"-Vorschläge und offene Rufe anderer Medics

Die Verbindung öffnet sich automatisch beim Beitritt auf einem GermanMiner-Server und bleibt für
die gesamte Sitzung bestehen — im und außer Dienst. Token-authentifiziert, Standard-Adresse
`wss://medic.dorikku.de/api`.

---

## Einstellungsmenü (/gmmenu)

Alles, was früher ein Chat-Befehl war (HUD, Fahrzeug-Automatik, API-Status/Token), lebt jetzt in
einem Menü:

- Öffnen mit **`/gmmenu`**, oder eine Taste dafür binden unter **Steuerung → GM Medic**
  (standardmäßig nicht belegt)
- Vier Bereiche: 🚑 **HUD-Einstellungen**, 🚗 **Fahrzeug-Automatik**, 📡 **API-Verwaltung**,
  ❤ **Auto-Antwort bei Wiederbelebung**

Nur die API-Server-Adresse bleibt ein Chat-Befehl, weil man sie am ehesten spontan ändern will
(z. B. Serverwechsel mitten in der Sitzung).

---

## Befehle

| Befehl | Beschreibung |
|---|---|
| `/gmmenu` | Einstellungsmenü öffnen |
| `/gmapi url <serverUrl>` | API-Server-Adresse setzen (Standard: `wss://medic.dorikku.de/api`); `off` deaktiviert die Verbindung |
| `/gmstatus` | Kurzüberblick: Dienststatus und Anzahl aktiver Rufe |

> Es gibt zusätzliche `/gm …`-Debug-Befehle, die **nur in einer Entwicklungsumgebung**
> registriert werden und im normalen Spiel nicht verfügbar sind. Sie sind für Spieler nicht
> relevant.

---

## Welche Version brauche ich?

Das Mod wird für mehrere Minecraft-Versionen parallel gepflegt. Lade die `.jar`, deren
Minecraft-Version zu deinem Client passt:

| Branch | Minecraft-Version | Java | Status |
|---|---|---|---|
| `1.21.10` | 1.21.10 (Fabric/Yarn) | 21+ | stabil |
| `1.21.11` | 1.21.11 (Fabric/Yarn) | 21+ | stabil |
| `Alpha-26-1.x` | 26.1.2 (Mojang-Mappings) | 25+ | **experimentell** |
| `Alpha-26.2` | 26.2 (Mojang-Mappings) | 25+ | **experimentell** |

Die `Alpha-*`-Versionen zielen auf Minecrafts neues Jahres-Versionsschema (26.x) mit offiziellen
Mojang-Mappings statt Yarn. Der Funktionsumfang ist identisch zu den 1.21.x-Versionen — die
Kennzeichnung „Alpha" bezieht sich auf die Reife der zugrunde liegenden Minecraft-Version und
Toolchain, nicht auf fehlende Mod-Funktionen.

---

## Fehlerbehebung / FAQ

**Das Mod tut nichts, obwohl ich im Dienst bin.**
Prüfe mit `/gmstatus`, ob das Mod deinen Dienststatus erkannt hat. Wurde der Dienstantritt nicht
über den üblichen Serverbefehl/Funk gemeldet, erkennt das Mod ihn nicht automatisch.

**Ich sehe keine Rufe/Highlights.**
Highlighting ist nur **im Dienst** aktiv. Prüfe außerdem die `Highlight-Reichweite` im HUD-Tab —
Rufe außerhalb dieser Reichweite werden nicht markiert.

**Die Fahrzeug-Automatik greift nicht.**
Jede Teilfunktion (Motor, Gang, Sirene, Sneak-Exit) hat einen eigenen Schalter im
Fahrzeug-Automatik-Tab. Prüfe, ob die jeweilige Funktion aktiviert ist und — falls du sie auch
außer Dienst nutzen willst — ob „immer aktiv" gesetzt ist.

**Ich bekomme keine „Nächster Medic"-Vorschläge oder API-Funktionen.**
Diese hängen an der Server-API-Verbindung. Prüfe im API-Verwaltung-Tab (oder `/gmapi url`), ob
die Verbindung „verbunden & authentifiziert" zeigt. Bist du nicht auf dem
[Medic-Fraktions-Roster](https://acp.germanminer.de/public/fraction/medic) gelistet, wirst du
nicht automatisch freigeschaltet.

**Welche Version soll ich installieren?**
Siehe [Welche Version brauche ich?](#welche-version-brauche-ich) — die Minecraft-Version deines
Clients entscheidet, nicht persönliche Vorliebe.
