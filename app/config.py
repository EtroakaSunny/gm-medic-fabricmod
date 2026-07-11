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
