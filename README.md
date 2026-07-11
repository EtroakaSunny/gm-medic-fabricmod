# GM Medic

A **client-side** Fabric mod for the GermanMiner medic / EMS roleplay. It reads the
server's radio (`[FUNK]`) and system messages, shows your active emergency calls on a
HUD, highlights the players who need help, and automates the fiddly vehicle steps so
you can focus on the response.

> Environment: **client only** · Requires **Fabric Loader**, **Fabric API**, **Java 21+**.
> The targeted Minecraft version depends on the branch you're on (see `gradle.properties`).

---

## Functions

- **Emergency call HUD** — an on-screen list of all active emergency calls (*Notrufe*)
  and death reports (*Todesmeldungen*), including caller, reason and assignment state.
  Supports a **compact** layout for large GUI scales.
- **Automatic chat parsing** — detects, straight from the GermanMiner `[FUNK]` and
  system chat: going **on/off duty**, **new emergency calls**, **death reports**, and a
  call being **accepted, rejected, reached or withdrawn** — no manual input needed.
- **Duty tracking** — knows whether you're on duty and resets automatically on
  disconnect.
- **Call-target highlighting** — draws an outline box around every player who has an
  open call so you can spot them in the world. Active only while on duty, within a
  configurable range, colour-coded by call type (death = red, emergency = orange).
- **Keyword highlighting** — also highlights (for 30 s) any player who writes
  `heal`, `heilung`, `leben` or `low` in public chat. The highlight is removed as soon
  as a medic bandages them (`… legt <player> einen Verband an`).
- **Vehicle automation** — when you enter an EMS vehicle the mod can:
  - **Motor** — run `/vehicles motor` automatically on entry.
  - **Gear** — drop the gear item the server hands you, to set the gear shift.
  - **Siren** — toggle `/vehicles sirene` on while you have an accepted call.
  - **Sneak-exit** — turn the siren off first and briefly delay your dismount when you
    sneak out (configurable delay). Helicopters are detected and skipped.

  Each feature can be toggled, and set to run **always** (even off duty).
- **Server API sync (optional)** — connects over WebSocket to an external GM-Medic
  server to sync duty state, calls (new / assigned / resolved / rejected), periodic
  location updates and keep-alive, and to receive nearest-medic / open-call data.
  Token-authenticated. Connects to `wss://medic.dorikku.de/api` by default; use
  `/gmapi url <serverUrl>` to point it elsewhere, or `/gmapi url off` to disable it.
  A blank `serverUrl` in `config/gm-medic-api.cfg` (as written by older versions) is
  migrated to the default on startup. Players listed on the [public GermanMiner
  medic-fraction roster](https://acp.germanminer.de/public/fraction/medic) are
  approved by the server automatically for the duration of their connection — no
  manual allow-listing needed.

---

## Commands

### Status

| Command | Description |
|---------|-------------|
| `/gmstatus` | Quick overview: duty state and number of active calls. |

### HUD — `/gmhud`

| Command | Description |
|---------|-------------|
| `/gmhud compact` | Toggle compact HUD layout (smaller display). |
| `/gmhud highlight` | Toggle highlighting of players with an open call. |
| `/gmhud highlight range <8-256>` | Set the highlight max distance (blocks). |
| `/gmhud status` | Show the current HUD settings. |

### Vehicle automation — `/gmvehicle`

| Command | Description |
|---------|-------------|
| `/gmvehicle motor on\|off` | Auto-start the motor on entry. |
| `/gmvehicle gear on\|off` | Auto-set the gear shift. |
| `/gmvehicle siren on\|off` | Auto-toggle the siren when you have a call. |
| `/gmvehicle <feature> always on\|off` | Keep a feature active even off duty. |
| `/gmvehicle exitdelay <1-200>` | Dismount delay in ticks (20 ticks = 1 s). |
| `/gmvehicle status` | Show the current vehicle-automation settings. |

### Server API — `/gmapi`

| Command | Description |
|---------|-------------|
| `/gmapi status` | Show connection state and configured server URL. |
| `/gmapi token` | Show your authentication token. |
| `/gmapi reset-token` | Generate a new token (reconnect required). |
| `/gmapi url <serverUrl>` | Set the API server URL (default: `wss://medic.dorikku.de/api`); `off` disables. |

---

## Developer / debug commands

> These are registered **only in a development environment** and are not available in
> a normal game. They simulate the server so the call system can be tested offline.
> All of them live under `/gm`.

| Command | Description |
|---------|-------------|
| `/gm duty` | Toggle duty on/off. |
| `/gm call <name> <reason>` | Add an emergency call directly. |
| `/gm death <name> <reason>` | Add a death report directly. |
| `/gm accept <caller> <medic>` | Assign a call to a medic. |
| `/gm remove <name>` | Remove a call. |
| `/gm simulate` | Create 3 test calls directly. |
| `/gm clear` | Reset everything (calls + duty). |
| `/gm status` | Show internal status. |
| `/gm testcall [name]` | Simulate the data-transmission for an emergency call. |
| `/gm testdeath [name]` | Simulate the data-transmission for a death report. |
| `/gm testduty` | Simulate the "entered duty" messages via the MessageHandler. |
| `/gm testoffduty` | Simulate the "left duty" messages. |
| `/gm testfunk` | Simulate a sequence of `[FUNK]` messages. |
| `/gm testfull` | Full GermanMiner simulation (`[FUNK]` format). |
| `/gm testrealformat` | Full simulation using the real GermanMiner radio format. |
| `/gm help` | List all debug commands in chat. |
