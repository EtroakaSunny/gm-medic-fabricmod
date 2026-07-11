# Deployment

The mod's default endpoint is `wss://medic.dorikku.de/api`, so the goal is:
that URL answers.

Default mode: the server runs as its **own compose stack next to
New-GM-API-Burner** on the same host and shares the burner's **Cloudflare
tunnel** — the app joins the burner's compose network (alias `gm-medic`) and a
second public hostname on the burner's named tunnel routes
`medic.dorikku.de` → `http://gm-medic:8765`. No open ports, no DNS A record,
TLS terminates at Cloudflare.

## Prerequisites

- Docker (and the compose plugin).
- New-GM-API-Burner deployed in **tunnel** mode with a **named** tunnel
  (`CLOUDFLARE_TUNNEL_TOKEN` set in its `.env`). Quick tunnels
  (`*.trycloudflare.com`) expose only one URL and cannot serve a second
  hostname.

## First deployment

```bash
git clone -b server https://github.com/<you>/gm-medic-fabricmod.git gm-medic-server
cd gm-medic-server
cp .env.example .env
nano .env                 # set GM_ADMIN_PASSWORD
docker compose up -d --build
```

`.env` defaults to burner mode (`COMPOSE_FILE` includes
`docker-compose.burner.yml`), which joins the app to the burner's compose
network. If `docker network ls` shows the burner's network under a name other
than `new-gm-api-burner_default`, set `BURNER_NETWORK=<name>` in `.env`.

Then route the hostname through the burner's tunnel: in the **Cloudflare Zero
Trust dashboard** open the burner's named tunnel and add a public hostname

```
medic.dorikku.de  →  http://gm-medic:8765
```

This also creates the DNS record automatically.

Verify:

```bash
docker compose ps                  # app healthy
docker compose logs -f app         # "GM-Medic server ready"
curl -sI https://medic.dorikku.de/ # 200, admin GUI
```

Then in Minecraft: join GermanMiner — the mod connects to `wss://<domain>/api`
on its own and chat shows *"[GM-Medic] Verbunden und authentifiziert."*
(players on the public fraction roster are approved automatically).

The app state lives in the `gm_medic_data` volume (SQLite DB + JWT secret),
so it survives rebuilds and updates.

## Updating

```bash
./update.sh
```

(fast-forwards the branch, rebuilds the image, restarts, prunes old images)

## Alternative: standalone with own Caddy

Without the burner (or without a tunnel), the stack can terminate TLS itself:

1. **DNS**: an A record for `medic.dorikku.de` → the host's IP; ports **80**
   and **443** open (Caddy needs 80 for the ACME challenge).
2. **`.env`**: switch to the standalone block:

   ```ini
   COMPOSE_PROFILES=standalone
   #COMPOSE_FILE=...      (commented out)
   ```

3. `docker compose up -d --build` — Caddy obtains the TLS certificate for
   `$DOMAIN` on first request and proxies everything (mod WebSocket `/api`,
   admin GUI, admin REST) to the app.

If the burner runs in **production** mode (its Caddy owns 80/443), keep burner
mode in `.env` but route via its Caddy instead of the tunnel: append
`medic.dorikku.de { reverse_proxy gm-medic:8765 }` to the burner's Caddyfile
and reload it (`docker compose exec caddy caddy reload --config
/etc/caddy/Caddyfile` from the burner's directory).

## Notes

- The admin GUI is at `https://<domain>/`, login with `GM_ADMIN_USER` /
  `GM_ADMIN_PASSWORD` from `.env`.
- To wipe all server state: `docker compose down -v` (deletes the DB volume).
- Standalone mode only: rootless Docker/Podman cannot bind ports 80/443 by
  default; either run compose as root or raise
  `net.ipv4.ip_unprivileged_port_start`.
