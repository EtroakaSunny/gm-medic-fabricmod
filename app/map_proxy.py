"""Read-only proxy for the public GermanMiner BlueMap.

The admin GUI uses the BlueMap tiles as the background of its live map, but the
browser cannot fetch the map host directly: it is served over plain HTTP (mixed
content on the HTTPS GUI) and sends no CORS headers. This router forwards a
small allow-listed set of GET paths and caches responses in memory so bursts of
tile requests don't hammer the upstream server.

Everything proxied here is already public on the internet, so no admin auth is
required (tile requests come from <img> elements that can't send JWT headers
anyway).
"""
import asyncio
import logging
import re
import time
import urllib.error
import urllib.request

from fastapi import APIRouter, HTTPException, Response

from . import config

log = logging.getLogger("gm-medic.map")

router = APIRouter()

_ALLOWED = re.compile(
    r"^(settings\.json"
    r"|maps/[A-Za-z0-9_.-]+/settings\.json"
    r"|maps/[A-Za-z0-9_.-]+/tiles/[0-9]/x-?[0-9]+/z-?[0-9]+\.png"
    r"|maps/[A-Za-z0-9_.-]+/live/(players|markers)\.json)$"
)

# Live data changes constantly; everything else (tiles, settings) barely does.
_TTL_LIVE_SECONDS = 2.0

# path -> (expires_at_monotonic, status, media_type, body)
_cache: dict[str, tuple[float, int, str, bytes]] = {}
_MAX_CACHE_ENTRIES = 2048


def _fetch(path: str) -> tuple[int, str, bytes]:
    url = f"{config.BLUEMAP_URL}/{path}"
    req = urllib.request.Request(url, headers={"User-Agent": "GM-Medic-Server"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            media_type = resp.headers.get("Content-Type", "application/octet-stream")
            return resp.status, media_type, resp.read()
    except urllib.error.HTTPError as e:
        # Upstream 404s (unrendered tiles) are normal — cache them too.
        return e.code, "text/plain", b""


def _prune_cache() -> None:
    if len(_cache) < _MAX_CACHE_ENTRIES:
        return
    now = time.monotonic()
    for key in [k for k, v in _cache.items() if v[0] < now]:
        _cache.pop(key, None)
    if len(_cache) >= _MAX_CACHE_ENTRIES:
        _cache.clear()  # blunt, but tiles simply reload on the next request


@router.get("/map/{path:path}")
async def map_proxy(path: str):
    if not _ALLOWED.match(path):
        raise HTTPException(status_code=404)

    now = time.monotonic()
    cached = _cache.get(path)
    if cached is not None and cached[0] > now:
        _, status, media_type, body = cached
    else:
        try:
            status, media_type, body = await asyncio.to_thread(_fetch, path)
        except Exception as e:
            log.warning("BlueMap fetch failed for %s: %s", path, e)
            raise HTTPException(status_code=502, detail="map upstream unreachable")
        ttl = _TTL_LIVE_SECONDS if "/live/" in path else config.BLUEMAP_CACHE_SECONDS
        _prune_cache()
        _cache[path] = (now + ttl, status, media_type, body)

    if status != 200:
        raise HTTPException(status_code=status)
    return Response(content=body, media_type=media_type,
                    headers={"Cache-Control": "public, max-age=60"})
