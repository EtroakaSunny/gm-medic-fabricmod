"""Password hashing (bcrypt) and admin JWT issue/verify."""
from datetime import datetime, timedelta, timezone

import bcrypt
import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from . import config

_bearer = HTTPBearer(auto_error=True)


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
    """Return (jwt, expires_in_seconds) for the given admin username."""
    expire = datetime.now(timezone.utc) + timedelta(hours=config.JWT_TTL_HOURS)
    payload = {"sub": subject, "exp": expire}
    token = jwt.encode(payload, config.SECRET_KEY, algorithm=config.JWT_ALGORITHM)
    return token, config.JWT_TTL_HOURS * 3600


def decode_token(token: str) -> dict:
    return jwt.decode(token, config.SECRET_KEY, algorithms=[config.JWT_ALGORITHM])


def get_current_admin(
    creds: HTTPAuthorizationCredentials = Depends(_bearer),
) -> str:
    """FastAPI dependency: validate the Bearer JWT and return the admin name."""
    try:
        payload = decode_token(creds.credentials)
    except jwt.PyJWTError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    subject = payload.get("sub")
    if not subject:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
    return subject


def verify_ws_token(token: str | None) -> str | None:
    """Validate a JWT passed as a query param on the admin WebSocket."""
    if not token:
        return None
    try:
        payload = decode_token(token)
    except jwt.PyJWTError:
        return None
    return payload.get("sub")
