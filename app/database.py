"""SQLite persistence layer.

Stores admin accounts, the medic allow-list, and bound TOFU tokens.
Live state (positions, active calls) is kept in memory — see ``state.py``.

A fresh connection is opened per operation; SQLite handles this cheaply and
it avoids cross-thread connection-sharing issues.
"""
import sqlite3
import time
from contextlib import contextmanager

from . import config

_SCHEMA = """
CREATE TABLE IF NOT EXISTS admins (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    created_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS medics (
    username     TEXT PRIMARY KEY,
    display_name TEXT,
    is_active    INTEGER NOT NULL DEFAULT 1,
    created_at   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS tokens (
    token      TEXT PRIMARY KEY,
    username   TEXT NOT NULL,
    bound_at   INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);
"""


@contextmanager
def _conn():
    config.DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(config.DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    with _conn() as conn:
        conn.executescript(_SCHEMA)


def _now_ms() -> int:
    return int(time.time() * 1000)


# --- Admin accounts ---

def count_admins() -> int:
    with _conn() as conn:
        return conn.execute("SELECT COUNT(*) AS c FROM admins").fetchone()["c"]


def create_admin(username: str, password_hash: str) -> None:
    with _conn() as conn:
        conn.execute(
            "INSERT INTO admins (username, password_hash, created_at) VALUES (?, ?, ?)",
            (username, password_hash, _now_ms()),
        )


def upsert_admin(username: str, password_hash: str) -> None:
    """Create the admin, or reset its password if it already exists."""
    with _conn() as conn:
        conn.execute(
            "INSERT INTO admins (username, password_hash, created_at) VALUES (?, ?, ?) "
            "ON CONFLICT(username) DO UPDATE SET password_hash = excluded.password_hash",
            (username, password_hash, _now_ms()),
        )


def get_admin(username: str) -> sqlite3.Row | None:
    with _conn() as conn:
        return conn.execute(
            "SELECT * FROM admins WHERE username = ?", (username,)
        ).fetchone()


# --- Medic allow-list ---

def add_medic(username: str, display_name: str | None) -> None:
    with _conn() as conn:
        conn.execute(
            "INSERT INTO medics (username, display_name, is_active, created_at) "
            "VALUES (?, ?, 1, ?) "
            "ON CONFLICT(username) DO UPDATE SET display_name = excluded.display_name, is_active = 1",
            (username, display_name, _now_ms()),
        )


def remove_medic(username: str) -> bool:
    with _conn() as conn:
        cur = conn.execute("DELETE FROM medics WHERE username = ?", (username,))
        return cur.rowcount > 0


def list_medics() -> list[dict]:
    with _conn() as conn:
        rows = conn.execute(
            "SELECT username, display_name, is_active, created_at FROM medics ORDER BY username"
        ).fetchall()
        return [dict(r) for r in rows]


def is_active_medic(username: str) -> bool:
    with _conn() as conn:
        row = conn.execute(
            "SELECT 1 FROM medics WHERE username = ? AND is_active = 1", (username,)
        ).fetchone()
        return row is not None


# --- TOFU tokens ---

def get_token(token: str) -> sqlite3.Row | None:
    with _conn() as conn:
        return conn.execute("SELECT * FROM tokens WHERE token = ?", (token,)).fetchone()


def bind_token(token: str, username: str) -> int:
    """Bind (or refresh) a token to a username; returns the new expiry (ms)."""
    now = _now_ms()
    expires = now + config.TOKEN_TTL_HOURS * 3600 * 1000
    with _conn() as conn:
        conn.execute(
            "INSERT INTO tokens (token, username, bound_at, expires_at) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(token) DO UPDATE SET username = excluded.username, expires_at = excluded.expires_at",
            (token, username, now, expires),
        )
    return expires


def delete_token(token: str) -> None:
    with _conn() as conn:
        conn.execute("DELETE FROM tokens WHERE token = ?", (token,))


def delete_tokens_for_user(username: str) -> int:
    with _conn() as conn:
        cur = conn.execute("DELETE FROM tokens WHERE username = ?", (username,))
        return cur.rowcount
