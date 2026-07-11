"""Auto-approval against the public GermanMiner medic-fraction roster.

Player names are read from the ``data-tippy-content`` attributes of the
member avatars on the fraction page (see ``config.ROSTER_URL``). The parsed
roster is cached for ``config.ROSTER_CACHE_SECONDS`` so bursts of connecting
clients don't hammer the ACP; on a fetch error the last good roster is kept.
"""
import asyncio
import logging
import re
import time
import urllib.request

from . import config

log = logging.getLogger("gm-medic.roster")

# Member avatars carry the player name as tooltip: data-tippy-content="Name"
_NAME_ATTR = re.compile(r'data-tippy-content="([^"]+)"')

_cached_names: frozenset[str] = frozenset()
_cached_at: float = float("-inf")


def _fetch_names() -> frozenset[str]:
    req = urllib.request.Request(
        config.ROSTER_URL, headers={"User-Agent": "GM-Medic-Server"}
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        html = resp.read().decode("utf-8", errors="replace")
    return frozenset(n.strip().lower() for n in _NAME_ATTR.findall(html) if n.strip())


async def is_fraction_member(username: str | None) -> bool:
    """True if the player name appears on the public fraction roster."""
    global _cached_names, _cached_at
    if not username:
        return False
    now = time.monotonic()
    if now - _cached_at > config.ROSTER_CACHE_SECONDS:
        try:
            names = await asyncio.to_thread(_fetch_names)
            _cached_names = names
            log.info("Fraction roster refreshed: %d member(s)", len(names))
        except Exception as e:
            log.warning("Fraction roster fetch failed (keeping last roster): %s", e)
        # Advance the timestamp even on failure so a dead ACP is retried at
        # most once per cache window instead of on every auth attempt.
        _cached_at = now
    return username.strip().lower() in _cached_names
