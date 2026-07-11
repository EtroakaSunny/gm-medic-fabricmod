# Deployment

Single VPS, Docker Compose, Caddy with automatic HTTPS. The mod's default
endpoint is `wss://medic.dorikku.de/api`, so the goal is: that URL answers.

## Prerequisites

- A VPS with Docker (and the compose plugin) installed.
- A DNS **A record** for `medic.dorikku.de` pointing at the VPS.
- Ports **80** and **443** open (Caddy needs 80 for the ACME challenge).

## First deployment

```bash
git clone -b server https://github.com/<you>/gm-medic-fabricmod.git gm-medic-server
cd gm-medic-server
cp .env.example .env
nano .env                 # set GM_ADMIN_PASSWORD (and DOMAIN if different)
docker compose up -d --build
```

That's it. Caddy obtains the TLS certificate for `$DOMAIN` on first request;
the app state lives in the `gm_medic_data` volume (SQLite DB + JWT secret),
so it survives rebuilds and updates.

Verify:

```bash
docker compose ps                  # both services healthy
docker compose logs -f app         # "GM-Medic server ready"
curl -sI https://medic.dorikku.de/ # 200, admin GUI
```

Then in Minecraft: go on duty — the mod connects to `wss://<domain>/api` on
its own and chat shows *"[GM-Medic] Verbunden und authentifiziert."* (players
on the public fraction roster are approved automatically).

## Updating

```bash
./update.sh
```

(fast-forwards the branch, rebuilds the image, restarts, prunes old images)

## Alongside New-GM-API-Burner on the same VPS

The burner's Caddy already owns ports 80/443, so the medic stack must not
start its own — share the burner's Caddy instead. The mod client only cares
that `wss://medic.dorikku.de/api` answers; who terminates TLS is irrelevant.

1. **DNS**: add an A record for `medic.dorikku.de` → the same VPS IP.
2. **`.env`**: use the burner-mode block from `.env.example`:

   ```ini
   COMPOSE_PROFILES=
   COMPOSE_FILE=docker-compose.yml:docker-compose.burner.yml
   ```

   This disables the bundled Caddy and joins the app to the burner's compose
   network (alias `gm-medic`). Check the network name with
   `docker network ls` — if it isn't `new-gm-api-burner_default`, set
   `BURNER_NETWORK=<name>` too.
3. **Start the app** (same command as always, `.env` does the rest):

   ```bash
   docker compose up -d --build
   ```

4. **Burner's Caddyfile**: append a second site block:

   ```caddyfile
   medic.dorikku.de {
       reverse_proxy gm-medic:8765
   }
   ```

5. **Reload the burner's Caddy** (from the burner's directory):

   ```bash
   docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile
   ```

   Caddy fetches the certificate for the new domain automatically.

`./update.sh` works unchanged in this mode. If the burner runs in **tunnel**
mode instead (cloudflared, no host ports), ports 80/443 are free — just use
the normal standalone setup, or add `medic.dorikku.de → http://gm-medic:8765`
as an extra public hostname of the named tunnel.

## Notes

- The admin GUI is at `https://<domain>/`, login with `GM_ADMIN_USER` /
  `GM_ADMIN_PASSWORD` from `.env`.
- To wipe all server state: `docker compose down -v` (deletes the DB volume).
- Rootless Docker/Podman cannot bind ports 80/443 by default; either run
  compose as root or raise `net.ipv4.ip_unprivileged_port_start`.
