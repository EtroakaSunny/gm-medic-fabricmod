"""Password hashing (bcrypt), JWT issue/verify, and role/permission checks.

Every authenticated request re-reads the account from the database, so
permission or role changes (and deletions) take effect on the next request —
no need to wait for the JWT to expire.
"""
import json
from datetime import datetime, timedelta, timezone

import bcrypt
import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from . import config, database

_bearer = HTTPBearer(auto_error=True)

# View permissions a non-admin account can hold. Admins implicitly hold all
# of them (plus user/medic management, which is role-gated, not permission-gated).
PERMISSIONS = ("medics", "map", "calls", "nav")


def hash_password(password: str) -> str:
    # bcrypt caps input at 72 bytes; truncate defensively.
    raw = password.encode("utf-8")[:72]
    return bcrypt.hashpw(raw, bcrypt.gensalt()).decode("utf-8")


def verify_password(password: str, password_hash: str) -> bool:
    raw = password.encode("utf-8")[:72]
    try:
        return bcrypt.checkpw(raw, password_hash.encode("utf-8"))
    except ValueError:
        return False


def create_access_token(subject: str) -> tuple[str, int]:
    """Return (jwt, expires_in_seconds) for the given username."""
    expire = datetime.now(timezone.utc) + timedelta(hours=config.JWT_TTL_HOURS)
    payload = {"sub": subject, "exp": expire}
    token = jwt.encode(payload, config.SECRET_KEY, algorithm=config.JWT_ALGORITHM)
    return token, config.JWT_TTL_HOURS * 3600


def decode_token(token: str) -> dict:
    return jwt.decode(token, config.SECRET_KEY, algorithms=[config.JWT_ALGORITHM])


def user_permissions(row) -> set[str]:
    """Effective view permissions of a users-table row."""
    if row["role"] == "admin":
        return set(PERMISSIONS)
    try:
        return set(json.loads(row["permissions"] or "[]")) & set(PERMISSIONS)
    except (TypeError, ValueError):
        return set()


def _resolve_user(username: str | None) -> dict | None:
    if not username:
        return None
    row = database.get_user(username)
    if row is None:
        return None
    return {
        "username": row["username"],
        "role": row["role"],
        "permissions": user_permissions(row),
    }


def get_current_user(
    creds: HTTPAuthorizationCredentials = Depends(_bearer),
) -> dict:
    """FastAPI dependency: validate the Bearer JWT and return the account
    as ``{username, role, permissions}`` (permissions as a set)."""
    try:
        payload = decode_token(creds.credentials)
    except jwt.PyJWTError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    user = _resolve_user(payload.get("sub"))
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Unknown user")
    return user


def get_current_admin(user: dict = Depends(get_current_user)) -> dict:
    """FastAPI dependency: like ``get_current_user`` but requires the admin role."""
    if user["role"] != "admin":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin-Rolle erforderlich")
    return user


def require_permission(perm: str):
    """Dependency factory: any authenticated account holding ``perm``."""
    def dep(user: dict = Depends(get_current_user)) -> dict:
        if perm not in user["permissions"]:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Keine Berechtigung")
        return user
    return dep


def verify_ws_token(token: str | None) -> dict | None:
    """Validate a JWT passed as a query param on the admin WebSocket; returns
    the account dict (see ``get_current_user``) or None."""
    if not token:
        return None
    try:
        payload = decode_token(token)
    except jwt.PyJWTError:
        return None
    return _resolve_user(payload.get("sub"))
