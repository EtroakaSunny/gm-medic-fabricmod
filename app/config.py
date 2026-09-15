"""Central configuration: paths, secret key, and lifetimes.

The JWT signing secret is generated once on first run and persisted to
``data/secret.key`` so that issued admin tokens survive server restarts.
"""
import os
import secrets
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = BASE_DIR / "data"
STATIC_DIR = BASE_DIR / "static"
DB_PATH = DATA_DIR / "gm-medic.db"
SECRET_PATH = DATA_DIR / "secret.key"

# Admin JWT settings
JWT_ALGORITHM = "HS256"
JWT_TTL_HOURS = 12

# Mod-client TOFU token binding lifetime
TOKEN_TTL_HOURS = 24

# How long a mod client has to send its AUTH frame after connecting
AUTH_TIMEOUT_SECONDS = 5

# Server bind defaults (overridable via env)
HOST = os.environ.get("GM_HOST", "0.0.0.0")
PORT = int(os.environ.get("GM_PORT", "8765"))

# Public GermanMiner fraction roster used to auto-approve mod clients whose
# player name is listed there (session-scoped, no DB entry needed).
ROSTER_URL = os.environ.get(
    "GM_ROSTER_URL", "https://acp.germanminer.de/public/fraction/medic"
)
ROSTER_CACHE_SECONDS = int(os.environ.get("GM_ROSTER_CACHE_SECONDS", "60"))

# Safety net: an open call (E-Call or DEATH) auto-resolves and is archived to
# history ("erledigt") if nobody handles it within this long.
CALL_TIMEOUT_MINUTES = float(os.environ.get("GM_CALL_TIMEOUT_MINUTES", "20"))

# When a call is assigned, if the medic's last known position is within this
# many blocks of the call, the assignment broadcast notes the medic is
# already nearby (see ws_mod.CALL_ASSIGNED).
MEDIC_NEARBY_THRESHOLD_BLOCKS = float(os.environ.get("GM_MEDIC_NEARBY_THRESHOLD_BLOCKS", "150"))

# A player may donate blood again this many seconds after their last donation.
# Nothing about a donation is kept past this window: the record is what makes
# the cooldown, so pruning it (state.prune_blood_draws) forgets the player
# entirely rather than leaving a "has donated" flag behind.
BLOOD_COOLDOWN_SECONDS = int(os.environ.get("GM_BLOOD_COOLDOWN_SECONDS", "3600"))

# How often the expired-donation sweep runs (see state.blood_prune_loop).
BLOOD_PRUNE_INTERVAL_SECONDS = int(os.environ.get("GM_BLOOD_PRUNE_INTERVAL_SECONDS", "60"))

# Latest published mod version — only the part before "+" in the mod's version
# string, e.g. "0.1.0-Beta" (see modversion.py). Left blank, update
# notifications are switched off entirely, so a forgotten deploy can't nag
# every medic about a version that doesn't exist yet.
LATEST_MOD_VERSION = os.environ.get("GM_LATEST_MOD_VERSION", "").strip()

# One general download link handed to every client regardless of branch — a page
# or folder the medic picks their build from. Blank means the notice names the
# new version but offers no link.
MOD_DOWNLOAD_URL = os.environ.get("GM_MOD_DOWNLOAD_URL", "").strip()

# Public GermanMiner BlueMap used as the admin GUI's map background. The GUI
# fetches it through this server (/map/...) because the map host is HTTP-only
# and sends no CORS headers. Cache lifetime applies to tiles and settings;
# live data (players/markers) is always cached for just 2 s.
BLUEMAP_URL = os.environ.get("GM_BLUEMAP_URL", "http://map.germanminer.de:2086").rstrip("/")
BLUEMAP_CACHE_SECONDS = int(os.environ.get("GM_BLUEMAP_CACHE_SECONDS", "300"))


def get_secret_key() -> str:
    """Load the JWT secret, generating and persisting one on first use."""
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    if SECRET_PATH.exists():
        return SECRET_PATH.read_text(encoding="utf-8").strip()
    secret = secrets.token_urlsafe(64)
    SECRET_PATH.write_text(secret, encoding="utf-8")
    try:
        os.chmod(SECRET_PATH, 0o600)
    except OSError:
        pass
    return secret


SECRET_KEY = get_secret_key()
