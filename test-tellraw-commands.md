# GM-Medic Tellraw Test Commands

Paste these into the Minecraft chat **as an operator** (or command block).
Each section tests a specific feature. Run them **in order** for a full test.

> **Note:** Replace `@a` with `@s` or a specific player selector if needed.

---

## 1. Duty On (direct)

```
/tellraw @a {"text":"§e┃ §620:58:12 §8» §r» ✔ Du bist nun im Dienst."}
```

Alternative:
```
/tellraw @a {"text":"Du bist jetzt im Dienst"}
```

---

## 2. Duty On (FUNK join — [FUNK] format)

Replace `PLAYERNAME` with your actual in-game name:
```
/tellraw @a {"text":"[FUNK] (Assistent) PLAYERNAME » Ich bin wieder auf dem Server! *Roger*"}
```

---

## 3. Duty On (FUNK join — Real Ⓛ format)

Replace `PLAYERNAME` with your actual in-game name:
```
/tellraw @a {"text":"Ⓛ [Notarzt] PLAYERNAME » Ich bin wieder auf dem Server! *Roger*"}
```

---

## 4. Duty On (TestDuty debug trigger)

```
/tellraw @a {"text":"TestDuty"}
```

---

## 5. Death Call — Full DATENÜBERMITTLUNG

Run these in quick succession (all lines of the transmission block):

```
/tellraw @a {"text":"[FUNK] ZENTRALE » Wir haben einen neuen Notruf erhalten - ich schicke euch die Daten rüber!"}
/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Betroffener: Toxic_padz"}
/tellraw @a {"text":" - Verbleibende Zeit: 4 Minuten, 58 Sekunden"}
/tellraw @a {"text":" - Todesursache: Tötungsdelikt"}
/tellraw @a {"text":" - Distanz: 2733 Meter"}
/tellraw @a {"text":"§e - Ortung: §f§fX: -1488 Y: 63 Z: -2095 (Offenbach Nord)"}
/tellraw @a {"text":" - Auf dem Weg: Niemand!"}
/tellraw @a {"text":""}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}
```

---

## 6. Normal Notruf — Full DATENÜBERMITTLUNG

```
/tellraw @a {"text":"[FUNK] ZENTRALE » Wir haben einen neuen Notruf erhalten - ich schicke euch die Daten rüber!"}
/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Notruf von: F3lixus"}
/tellraw @a {"text":" - Erstellt: 02.03.2026, 20:58"}
/tellraw @a {"text":" - Grund: ich brauche heal"}
/tellraw @a {"text":" - Distanz: 2310 Meter"}
/tellraw @a {"text":"§e - Ortung: §f§fX: -1332 Y: 82 Z: -550 (Südlicher Gebirgszug)"}
/tellraw @a {"text":" - Auf dem Weg: Niemand!"}
/tellraw @a {"text":""}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}
```

---

## 7. Notruf with "Von:" header (alternative caller format)

```
/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Von: SteveHeal"}
/tellraw @a {"text":" - Grund: Beinbruch am Marktplatz"}
/tellraw @a {"text":"§e - Ortung: §f§fX: 500 Y: 70 Z: -200 (Marktplatz)"}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}
```

---

## 8. Accept a Call ([FUNK] format)

Accepts the call from **F3lixus** by medic **mmlp12345**:
```
/tellraw @a {"text":"[FUNK] (Facharzt) mmlp12345 » Ich nehme den Notruf von F3lixus entgegen!"}
```

---

## 9. Accept a Call (Real Ⓛ format)

Accepts the call from **Toxic_padz** by medic **DrHouse**:
```
/tellraw @a {"text":"Ⓛ [Facharzt] DrHouse » Ich nehme den Notruf von Toxic_padz entgegen!"}
```

---

## 10. Reject a Call

Rejects the call from **SteveHeal** by medic **NurseJoy**:
```
/tellraw @a {"text":"[FUNK] (Sanitäter) NurseJoy » Ich habe den Notruf von SteveHeal zurückgewiesen"}
```

---

## 11. Withdraw a Call (caller withdraws their own Notruf)

```
/tellraw @a {"text":"[FUNK] ZENTRALE » Der Spieler F3lixus hat seinen Notruf zurückgezogen."}
```

---

## 12. Revive a Player

```
/tellraw @a {"text":"[FUNK] (Sanitäter) DrHouse » Ich habe Toxic_padz wiederbelebt!"}
```

---

## 13. Caller Logged Out

```
/tellraw @a {"text":"[FUNK] ZENTRALE » Der Spieler SteveHeal hat sich ausgeloggt."}
```

---

## 14. Reached the Caller

```
/tellraw @a {"text":"[FUNK] (Facharzt) mmlp12345 » Ich habe den Notruf in der Innenstadt (F3lixus) erreicht!"}
```

---

## 15. Duty Off (direct)

```
/tellraw @a {"text":"Du hast den Dienst verlassen."}
```

Alternative:
```
/tellraw @a {"text":"Du bist nicht mehr im Dienst"}
```

---

## 16. Duty Off (FUNK leave — [FUNK] format)

Replace `PLAYERNAME` with your actual in-game name:
```
/tellraw @a {"text":"[FUNK] (Assistent) PLAYERNAME » Ich bin nicht mehr im Dienst. Bis dann!"}
```

---

## 17. Duty Off (FUNK leave — Real Ⓛ format)

Replace `PLAYERNAME` with your actual in-game name:
```
/tellraw @a {"text":"Ⓛ [Notarzt] PLAYERNAME » Ich bin nun offline. Bis bald!"}
```

---

## 18. HUD Compact Mode Toggle

These are client commands (not tellraw), type them in chat:
```
/gmhud compact
/gmhud status
```

---

## 19. Death Call with Short Timer (test flashing)

```
/tellraw @a {"text":"TestDuty"}
/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Betroffener: DyingPlayer"}
/tellraw @a {"text":" - Verbleibende Zeit: 25 Sekunden"}
/tellraw @a {"text":" - Todesursache: Explosion"}
/tellraw @a {"text":"§e - Ortung: §f§fX: 100 Y: 64 Z: 200"}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}
```

---

## 20. Death Call with Very Long Timer (green)

```
/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Betroffener: ChillPlayer"}
/tellraw @a {"text":" - Verbleibende Zeit: 4 Minuten, 30 Sekunden"}
/tellraw @a {"text":" - Todesursache: Ertrunken"}
/tellraw @a {"text":"§e - Ortung: §f§fX: -5000 Y: 40 Z: 3000 (Hafen)"}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}
```

---

## 21. Multiple Calls Stress Test (tests scrolling / overlap)

Run all of these in quick sequence to fill the HUD:

```
/tellraw @a {"text":"TestDuty"}

/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Notruf von: Call_Player_1"}
/tellraw @a {"text":" - Grund: Autounfall"}
/tellraw @a {"text":"§e - Ortung: §f§fX: 100 Y: 64 Z: 200 (Autobahn A1)"}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}

/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Betroffener: Call_Player_2"}
/tellraw @a {"text":" - Verbleibende Zeit: 2 Minuten, 15 Sekunden"}
/tellraw @a {"text":" - Todesursache: Sturz aus grosser Hoehe"}
/tellraw @a {"text":"§e - Ortung: §f§fX: -800 Y: 120 Z: -1500 (Bergwerk)"}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}

/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Notruf von: Call_Player_3"}
/tellraw @a {"text":" - Grund: Ich bin verloren und brauche hilfe bitte kommt schnell"}
/tellraw @a {"text":"§e - Ortung: §f§fX: 9999 Y: 55 Z: -9999"}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}

/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Betroffener: Call_Player_4"}
/tellraw @a {"text":" - Verbleibende Zeit: 45 Sekunden"}
/tellraw @a {"text":" - Todesursache: PvP"}
/tellraw @a {"text":"§e - Ortung: §f§fX: -12345 Y: 63 Z: 67890 (Weit weg)"}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}
```

Then test accept/reject/remove on them:
```
/tellraw @a {"text":"[FUNK] (Facharzt) MedicA » Ich nehme den Notruf von Call_Player_1 entgegen!"}
/tellraw @a {"text":"[FUNK] (Sanitäter) MedicB » Ich habe den Notruf von Call_Player_3 zurückgewiesen"}
/tellraw @a {"text":"[FUNK] ZENTRALE » Der Spieler Call_Player_4 hat seinen Notruf zurückgezogen."}
```

---

## 22. GermanMiner Server Format with Timestamps

Tests the full `§e┃ §6TIMESTAMP §8» §r` prefix format:

```
/tellraw @a {"text":"§e┃ §620:58:12 §8» §r» ✔ Du bist nun im Dienst."}
/tellraw @a {"text":"§e┃ §620:58:30 §8» §r  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":"§e┃ §620:58:30 §8» §r - Betroffener: TimestampPlayer"}
/tellraw @a {"text":"§e┃ §620:58:30 §8» §r - Verbleibende Zeit: 3 Minuten, 0 Sekunden"}
/tellraw @a {"text":"§e┃ §620:58:30 §8» §r - Todesursache: Vergiftung"}
/tellraw @a {"text":"§e┃ §620:58:30 §8» §r§e - Ortung: §f§fX: -500 Y: 72 Z: 1000 (Krankenhaus)"}
/tellraw @a {"text":"§e┃ §620:58:30 §8» §r - Auf dem Weg: Niemand!"}
/tellraw @a {"text":"§e┃ §620:58:30 §8» §r"}
/tellraw @a {"text":"§e┃ §620:58:30 §8» §r  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}
```

---

## 23. Location without Name (tests coord-only display)

```
/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Notruf von: NoLocPlayer"}
/tellraw @a {"text":" - Grund: Hilfe benötigt"}
/tellraw @a {"text":"§e - Ortung: §f§fX: 250 Y: 80 Z: -300"}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}
```

---

## 24. Reset Everything

```
/tellraw @a {"text":"Du hast den Dienst verlassen."}
```

---

## Quick Full Test Sequence (Copy-Paste Friendly)

Run all of these in order for a complete feature test:

```
/tellraw @a {"text":"TestDuty"}
/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Betroffener: Toxic_padz"}
/tellraw @a {"text":" - Verbleibende Zeit: 4 Minuten, 58 Sekunden"}
/tellraw @a {"text":" - Todesursache: Tötungsdelikt"}
/tellraw @a {"text":"§e - Ortung: §f§fX: -1488 Y: 63 Z: -2095 (Offenbach Nord)"}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}
/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Notruf von: F3lixus"}
/tellraw @a {"text":" - Grund: ich brauche heal"}
/tellraw @a {"text":"§e - Ortung: §f§fX: -1332 Y: 82 Z: -550 (Südlicher Gebirgszug)"}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}
/tellraw @a {"text":"[FUNK] (Facharzt) mmlp12345 » Ich nehme den Notruf von F3lixus entgegen!"}
/tellraw @a {"text":"Ⓛ [Facharzt] DrHouse » Ich nehme den Notruf von Toxic_padz entgegen!"}
/tellraw @a {"text":"  ----- DATENÜBERMITTLUNG VON ZENTRALE -----"}
/tellraw @a {"text":" - Notruf von: RejectMe"}
/tellraw @a {"text":" - Grund: Testing rejection"}
/tellraw @a {"text":"§e - Ortung: §f§fX: 0 Y: 64 Z: 0"}
/tellraw @a {"text":"  §aANNEHMEN      §eANRUFEN      §cMELDEN      §4ZURÜCKWEISEN"}
/tellraw @a {"text":"[FUNK] (Sanitäter) NurseJoy » Ich habe den Notruf von RejectMe zurückgewiesen"}
/tellraw @a {"text":"[FUNK] (Sanitäter) DrHouse » Ich habe Toxic_padz wiederbelebt!"}
/tellraw @a {"text":"[FUNK] ZENTRALE » Der Spieler F3lixus hat seinen Notruf zurückgezogen."}
```

