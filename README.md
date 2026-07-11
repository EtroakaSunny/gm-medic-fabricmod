# GM-Medic Server

FastAPI backend for the GM-Medic Fabric mod: receives duty state, live locations
and emergency calls from connected mod clients, syncs calls between all clients,
computes the nearest free medic, and serves an admin GUI.

> Deployed for the mod's default endpoint `wss://medic.dorikku.de/api`
> (TLS terminated by a reverse proxy in front of the app).

## Run

Production: see **[DEPLOYMENT.md](DEPLOYMENT.md)** (Docker Compose + Caddy,
automatic HTTPS). Local development:

```bash
python -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python run.py            # listens on 0.0.0.0:8765
```

### Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `GM_HOST` / `GM_PORT` | `0.0.0.0` / `8765` | Bind address. |
| `GM_ADMIN_USER` / `GM_ADMIN_PASSWORD` | `admin` / generated | Admin GUI account (password synced on startup when set). |
| `GM_SSL_CERT` / `GM_SSL_KEY` | – | Serve TLS directly instead of via reverse proxy. |
| `GM_ROSTER_URL` | `https://acp.germanminer.de/public/fraction/medic` | Public fraction roster used for auto-approval. |
| `GM_ROSTER_CACHE_SECONDS` | `60` | Roster cache lifetime. |

## Endpoints

| Path | What |
|------|------|
| `WS /api` (alias `WS /ws`) | Mod clients. `/api` matches the mod's default URL. |
| `WS /ws/admin?token=<jwt>` | Admin GUI live feed (snapshot + updates). |
| `REST /api/...` | Admin REST (login, medic allow-list, tokens, calls, online). |
| `/` | Static admin GUI. |

## Mod-client authentication

Two ways to get approved; both require the first frame to be
`{"type":"AUTH","token":"<uuid>","username":"<mc-name>"}` within 5 s:

1. **Allow-list (TOFU)** — if the username is on the medic allow-list
   (`/api/medics`, managed by an admin), the client's self-generated token is
   bound to that username for 24 h and refreshed on each connect.
2. **Fraction roster (automatic)** — if the username is *not* on the
   allow-list but appears on the public GermanMiner fraction roster
   (player names parsed from the member avatars' `data-tippy-content`
   attributes), the client is approved **for that connection only**: nothing
   is persisted, `expiresAt` is `null` in `AUTH_OK`, and the approval ends
   when the connection closes. The roster is re-fetched at most once per
   `GM_ROSTER_CACHE_SECONDS`; on fetch errors the last good roster is reused.
   Name comparison is case-insensitive.

## Client sync

- After `AUTH_OK` the server immediately sends `OPEN_CALLS` with every
  unresolved call, so a newly connected client starts with the full picture.
- `DUTY_ON` re-sends `OPEN_CALLS` (calls may have arrived while off duty).
- Every call mutation reported by one client (`CALL_NEW`, `CALL_ASSIGNED`,
  `CALL_RESOLVED`, `CALL_REJECTED`) is fanned out to all other connected mod
  clients as `CALL_SYNC`, and to admin GUIs as `call_update`.
- `CALL_NEW` additionally answers the reporter with `NEAREST_MEDIC`.

Live state (positions, calls) is in-memory; SQLite (`data/gm-medic.db`) stores
admin accounts, the allow-list and bound tokens. `data/secret.key` is the JWT
signing secret — keep it to preserve admin sessions across restarts.
