"""SQLite persistence layer.

Stores GUI user accounts (with role and view permissions), the medic
allow-list, and bound TOFU tokens. Live state (positions, active calls) is
kept in memory — see ``state.py``.

A fresh connection is opened per operation; SQLite handles this cheaply and
it avoids cross-thread connection-sharing issues.
"""
import json
import sqlite3
import time
from contextlib import contextmanager

from . import config

_SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    username      TEXT PRIMARY KEY,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'user',
    permissions   TEXT NOT NULL DEFAULT '[]',
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

CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Active and recent (last 24h) resolved calls, so a restart or crash doesn't
-- wipe the dispatch board the way losing state.py's in-memory dicts would.
-- The full call dict is kept as JSON since its shape has grown ad hoc
-- (assignedMedic, suggestedMedic, resolveReason, ...) and keeping this table
-- in lockstep with every new field would be pure overhead for a value
-- nothing here queries by.
CREATE TABLE IF NOT EXISTS calls (
    call_id   TEXT PRIMARY KEY,
    resolved  INTEGER NOT NULL DEFAULT 0,
    timestamp INTEGER NOT NULL,
    data      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_calls_timestamp ON calls (timestamp);
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
        # Accounts used to live in an "admins" table without roles — migrate
        # them once as full admins, then drop the legacy table.
        legacy = conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='admins'"
        ).fetchone()
        if legacy:
            conn.execute(
                "INSERT OR IGNORE INTO users (username, password_hash, role, permissions, created_at) "
                "SELECT username, password_hash, 'admin', '[]', created_at FROM admins"
            )
            conn.execute("DROP TABLE admins")


def _now_ms() -> int:
    return int(time.time() * 1000)


# --- GUI user accounts ---

def count_admins() -> int:
    with _conn() as conn:
        return conn.execute(
            "SELECT COUNT(*) AS c FROM users WHERE role = 'admin'"
        ).fetchone()["c"]


def upsert_admin(username: str, password_hash: str) -> None:
    """Bootstrap: create the admin, or reset its password and re-assert the role."""
    with _conn() as conn:
        conn.execute(
            "INSERT INTO users (username, password_hash, role, created_at) VALUES (?, ?, 'admin', ?) "
            "ON CONFLICT(username) DO UPDATE SET password_hash = excluded.password_hash, role = 'admin'",
            (username, password_hash, _now_ms()),
        )


def get_user(username: str) -> sqlite3.Row | None:
    with _conn() as conn:
        return conn.execute(
            "SELECT * FROM users WHERE username = ?", (username,)
        ).fetchone()


def list_users() -> list[sqlite3.Row]:
    with _conn() as conn:
        return conn.execute(
            "SELECT username, role, permissions, created_at FROM users ORDER BY username"
        ).fetchall()


def create_user(username: str, password_hash: str, role: str, permissions: list[str]) -> bool:
    """Returns False if the username is already taken."""
    with _conn() as conn:
        try:
            conn.execute(
                "INSERT INTO users (username, password_hash, role, permissions, created_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (username, password_hash, role, json.dumps(sorted(set(permissions))), _now_ms()),
            )
            return True
        except sqlite3.IntegrityError:
            return False


def update_user(username: str, role: str | None = None,
                permissions: list[str] | None = None) -> bool:
    with _conn() as conn:
        if role is not None:
            conn.execute("UPDATE users SET role = ? WHERE username = ?", (role, username))
        if permissions is not None:
            conn.execute(
                "UPDATE users SET permissions = ? WHERE username = ?",
                (json.dumps(sorted(set(permissions))), username),
            )
        return conn.execute(
            "SELECT 1 FROM users WHERE username = ?", (username,)
        ).fetchone() is not None


def set_user_password(username: str, password_hash: str) -> None:
    with _conn() as conn:
        conn.execute(
            "UPDATE users SET password_hash = ? WHERE username = ?",
            (password_hash, username),
        )


def delete_user(username: str) -> bool:
    with _conn() as conn:
        cur = conn.execute("DELETE FROM users WHERE username = ?", (username,))
        return cur.rowcount > 0


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


# --- Calls (active + recent history, survives restarts) ---

def save_call(call: dict) -> None:
    with _conn() as conn:
        conn.execute(
            "INSERT INTO calls (call_id, resolved, timestamp, data) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(call_id) DO UPDATE SET resolved = excluded.resolved, "
            "timestamp = excluded.timestamp, data = excluded.data",
            (
                call["callId"],
                int(bool(call.get("resolved"))),
                call.get("timestamp") or _now_ms(),
                json.dumps(call),
            ),
        )


def load_calls() -> list[dict]:
    with _conn() as conn:
        rows = conn.execute("SELECT data FROM calls").fetchall()
        return [json.loads(r["data"]) for r in rows]


def prune_calls_older_than(cutoff_ms: int) -> int:
    """Deletes resolved calls whose ``timestamp`` predates ``cutoff_ms``.
    Open calls are never touched here regardless of age."""
    with _conn() as conn:
        cur = conn.execute(
            "DELETE FROM calls WHERE resolved = 1 AND timestamp < ?", (cutoff_ms,)
        )
        return cur.rowcount


# --- Server-wide settings (key/value, admin-editable at runtime) ---

def get_setting(key: str, default: str = "") -> str:
    with _conn() as conn:
        row = conn.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
        return row["value"] if row is not None else default


def set_setting(key: str, value: str) -> None:
    with _conn() as conn:
        conn.execute(
            "INSERT INTO settings (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )
