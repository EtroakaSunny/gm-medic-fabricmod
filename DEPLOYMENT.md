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

## Notes

- The admin GUI is at `https://<domain>/`, login with `GM_ADMIN_USER` /
  `GM_ADMIN_PASSWORD` from `.env`.
- To wipe all server state: `docker compose down -v` (deletes the DB volume).
- Rootless Docker/Podman cannot bind ports 80/443 by default; either run
  compose as root or raise `net.ipv4.ip_unprivileged_port_start`.
