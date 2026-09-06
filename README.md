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

  Each feature can be toggled, and set to run **always** (even off duty), from the
  settings menu (`/gmmenu`).
- **Microscope diagnosis checklist** — while the server's `Mikroskop | <patient>` menu is
  open, a panel next to it lists all 16 Minecraft dye colours with a checkbox each, so you can
  tick off what you saw. The ticks are kept **per patient** and survive paging through the menu
  (which closes and reopens it), for up to **5 minutes** from the start of that run — long
  enough for one diagnosis, short enough that a re-taken sample never inherits old notes. They
  are also dropped when you go off duty or leave the server.
  - **Hints** — if a diagnosis takes longer than **60 seconds** and your ticks don't match the
    sample, the panel starts nudging you and gets one step more specific every 30 s: something
    is off → how much → roughly which colour range → the colour by name. It never ticks a box
    for you, and when nothing is wrong it stays quiet.
  - **Layouts** — a full-height list, or a **compact** grid for the very large GUI scales some
    medics play at. The compact one is also picked automatically whenever the list would not
    fit beside the menu.
  - **Labels** — either the colour's name written in that colour, or Minecraft's own dye icon.
  - Entirely local: the checklist is never synced to the API server, and the server side needs
    to know nothing about it.
- **Revive auto-reply** — optionally sends an automatic public chat reply right after
  *your own* "Ich habe X wiederbelebt!" message — only for the medic who actually did
  the reviving, never for other on-duty medics who just see the broadcast. Toggle and
  message text are both in the settings menu.
- **Server API sync (optional)** — connects over WebSocket to an external GM-Medic
  server to sync duty state, calls (new / assigned / resolved / rejected), periodic
  location updates and keep-alive, and to receive nearest-medic / open-call data.
  The connection opens automatically when you join a GermanMiner server (address
  containing `germanminer.de`) and stays up for the whole game session, on and off
  duty.
  Token-authenticated. Connects to `wss://medic.dorikku.de/api` by default; use
  `/gmapi url <serverUrl>` to point it elsewhere, or `/gmapi url off` to disable it.
  A blank `serverUrl` in `config/gm-medic-api.cfg` (as written by older versions) is
  migrated to the default on startup. Players listed on the [public GermanMiner
  medic-fraction roster](https://acp.germanminer.de/public/fraction/medic) are
  approved by the server automatically for the duration of their connection — no
  manual allow-listing needed.

---

## Settings menu

Every setting that used to be a chat command — HUD, vehicle automation, and API status/token
management — now lives in an in-game menu instead:

- Open it with **`/gmmenu`**, or bind a key to it in **Controls → GM Medic** (unbound by
  default).
- The menu has four tabs (HUD, vehicle automation, microscope diagnosis, API) plus the revive
  auto-reply toggle described below.
- **Mikroskop-Diagnose** holds the three checklist settings: whether it is shown at all, the
  compact layout, and whether entries are colour names or dye icons. All three are local
  display choices.

Only the API server URL stays a command, since it's the one setting you'd want to change
without leaving the game (e.g. switching servers mid-session):

| Command | Description |
|---------|-------------|
| `/gmapi url <serverUrl>` | Set the API server URL (default: `wss://medic.dorikku.de/api`); `off` disables. |

### Status

| Command | Description |
|---------|-------------|
| `/gmstatus` | Quick overview: duty state and number of active calls. |

### Revive auto-reply

When enabled (toggle in `/gmmenu`), the mod sends an automatic public chat reply right after
**your own** "Ich habe X wiederbelebt!" message — i.e. only when *you* are the medic who
revived or healed the player, never when you merely see another medic's broadcast. The
message text is editable in the same menu; `{player}` is replaced with the revived player's
name.

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
| `/gm mikroskop [name]` | Open a stand-in `Mikroskop \| <name>` menu holding a random sample (0–2 colours missing), to try the checklist and its hints offline. |
| `/gm help` | List all debug commands in chat. |
