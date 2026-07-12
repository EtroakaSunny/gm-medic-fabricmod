# GM-Medic Server

FastAPI backend for the GM-Medic Fabric mod: receives duty state, live locations
and emergency calls from connected mod clients, syncs calls between all clients,
computes the nearest free medic, and serves an admin GUI.

> Deployed for the mod's default endpoint `wss://medic.dorikku.de/api`
> (TLS terminated by a reverse proxy in front of the app).

## Run

```bash
python -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python run.py            # listens on 0.0.0.0:8765
```

Or as a container (image `localhost/gm-medic-server`).

## Deployment

Runs as its own compose stack (this branch: `docker compose up -d --build`)
next to **New-GM-API-Burner** on the same host, sharing the burner's named
Cloudflare tunnel: the `docker-compose.burner.yml` overlay joins the app to
the burner's compose network (alias `gm-medic`), and a second public hostname
on the tunnel routes `medic.dorikku.de` → `http://gm-medic:8765` (configured
in the Cloudflare Zero Trust dashboard; TLS terminates at Cloudflare, so the
mod's default `wss://medic.dorikku.de/api` works unchanged). State lives in
the named volume `gm_medic_data` (mounted at `/app/data`). See DEPLOYMENT.md
for the full walkthrough and the standalone (own Caddy) alternative.

### Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `GM_HOST` / `GM_PORT` | `0.0.0.0` / `8765` | Bind address. |
| `GM_ADMIN_USER` / `GM_ADMIN_PASSWORD` | `admin` / generated | Admin GUI account (password synced on startup when set). |
| `GM_SSL_CERT` / `GM_SSL_KEY` | – | Serve TLS directly instead of via reverse proxy. |
| `GM_ROSTER_URL` | `https://acp.germanminer.de/public/fraction/medic` | Public fraction roster used for auto-approval. |
| `GM_ROSTER_CACHE_SECONDS` | `60` | Roster cache lifetime. |
| `GM_BLUEMAP_URL` | `http://map.germanminer.de:2086` | Public BlueMap proxied for the GUI's live map. |
| `GM_BLUEMAP_CACHE_SECONDS` | `300` | Tile/settings cache lifetime (live data: 2 s). |
| `GM_NAV_MIN_SPEED` | `3.0` | Blocks/s below which a driving sample is not learned as street (see Beta navigation). |

## Endpoints

| Path | What |
|------|------|
| `WS /api` (alias `WS /ws`) | Mod clients. `/api` matches the mod's default URL. |
| `WS /ws/admin?token=<jwt>` | GUI live feed (snapshot + updates, filtered by permissions). |
| `REST /api/...` | GUI REST (login, account, users, medic allow-list, tokens, calls, online). |
| `GET /map/...` | Read-only proxy for the public GermanMiner BlueMap (see below). |
| `/` | Static GUI. |

## GUI accounts, roles and permissions

The GUI has its own accounts (`users` table; legacy `admins` tables are
migrated automatically as admins). Two roles:

- **admin** — sees everything and additionally manages medics (allow-list,
  token revocation) and users (create/delete, role, permissions, password
  reset). The bootstrap account from `GM_ADMIN_USER`/`GM_ADMIN_PASSWORD` is
  an admin.
- **user** — read-only viewer. What they see is picked per account from the
  view permissions `medics` (roster panel), `map` (live map), `calls`
  (call list), `nav` (beta navigation tab).

Permissions are enforced server-side: REST routes check them per request and
the `/ws/admin` feed only carries updates the account may see (the `map`
permission implies receiving medic and call positions, since the map draws
them). Role or permission changes take effect on the account's next request;
an already-open WebSocket keeps its filter until it reconnects.

Everyone manages their own password in the GUI's **Konto** tab
(`POST /api/me/password`, needs the current password, min. 8 characters).
Admins cannot delete themselves or drop their own admin role, so the last
admin can't lock everyone out. User management lives under `/api/users`
(GET/POST/PATCH/DELETE, admin only); `GET /api/me` returns the caller's role
and permissions.

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
- Only **on-duty** medics are position-tracked: `LOCATION_UPDATE` from an
  off-duty client is ignored, and `DUTY_OFF` drops the stored position.
  Each update carries a `driving` flag, true while the client detects the
  player sitting in a car (vehicle control item in the hotbar; helicopters
  carry a `Helikopter-Menü` instead and are excluded).
- Every call mutation reported by one client (`CALL_NEW`, `CALL_ASSIGNED`,
  `CALL_RESOLVED`, `CALL_REJECTED`) is fanned out to all other connected mod
  clients as `CALL_SYNC`, and to admin GUIs as `call_update`.
- `CALL_NEW` additionally computes the nearest on-duty medic (reporter
  included, the call's own caller excluded), stores it as the call's
  `suggestedMedic` and broadcasts `NEAREST_MEDIC` to **all** mod clients,
  which show it in chat as `GM-Medic: Der nächste Medic ist: <name>`.
- `ALARM_TRIGGERED` (bank alarm read from the D-Funk) stores the alarm and fans
  it out as `ALARM_SYNC` to every client **not on duty** (admin GUIs get
  `alarm_update`); duplicate reports of the same alarm are ignored.
  `ALARM_ENDED` clears it and notifies all clients. Newly connected clients and
  clients going off duty receive the active alarm immediately; a 30-minute
  timeout drops alarms whose end message was missed.

## Beta: street navigation

The GUI's **Navigation** tab (beta) learns GermanMiner's street network from
the medics' own drives — no block data, no extra client work. On-duty
`LOCATION_UPDATE`s tagged `driving: true` (in a car; helicopters and foot
traffic never qualify) are joined into movement segments, rasterised onto a
4-block grid and stored as **directed** edges with traversal count and average
speed (`app/nav.py`, persisted to `data/nav-graph.json`, autosaved every
5 min). Grid cells also carry a coarse 10-block vertical layer, so a tunnel
and the road above it stay separate roads (no phantom junction where they
cross) while ramps still connect the layers. Filters keep the graph clean: segments slower than
`GM_NAV_MIN_SPEED`, faster than 40 blocks/s (teleport), with >12 blocks
vertical jump or >10 s gaps are dropped, and the track breaks on duty-off or
disconnect.

Routing (`GET /api/nav/route?fromX=&fromZ=&toX=&toZ=`) snaps both points to
the nearest learned node (≤96 blocks) and runs Dijkstra over travel time
(edge length ÷ learned speed), preferring edges driven at least twice and
relaxing to once when the strict graph is not connected yet. `GET
/api/nav/graph` returns the display edges, `GET /api/nav/stats` the counters;
all three require an admin JWT. In the GUI: open the Navigation tab, click
"Straßennetz anzeigen" to see what has been learned (grey/orange/green by
speed), then click start and destination on the map to get a route with
distance and estimated drive time.

## Live map (BlueMap proxy)

The admin GUI renders a Leaflet map (vendored under `static/vendor/leaflet/`)
with medic and call markers on top of GermanMiner's public **BlueMap**
(`GM_BLUEMAP_URL`). The browser cannot load that host directly — it is
HTTP-only (mixed content on the HTTPS GUI) and sends no CORS headers — so
`GET /map/{path}` forwards an allow-listed set of paths (low-res tiles,
map settings, live players/markers JSON) with an in-memory cache
(`GM_BLUEMAP_CACHE_SECONDS`; live data 2 s). The proxy is unauthenticated:
everything behind it is already public. BlueMap details worth knowing:
low-res tiles are 500×1000 PNGs (color map on top, data map below — the GUI
crops to the top half), 1 px = 1 block at LOD 1, and each LOD step zooms out
by 5× (the GUI uses a custom Leaflet CRS with scale factor 5 so BlueMap's
tile grid lines up exactly).

Live state (positions, calls) is in-memory; SQLite (`data/gm-medic.db`) stores
admin accounts, the allow-list and bound tokens. `data/secret.key` is the JWT
signing secret — keep it to preserve admin sessions across restarts.
