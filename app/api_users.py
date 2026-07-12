"""Account self-service (/api/me) and admin-only user management (/api/users).

Non-admin accounts are read-only viewers: their ``permissions`` list picks
which parts of the GUI (and its data feed) they can see. Admins hold every
permission implicitly and additionally manage users and medics.
"""
import json

from fastapi import APIRouter, Depends, HTTPException, status

from . import database
from .models import PasswordChange, UserCreate, UserOut, UserPatch
from .security import (
    PERMISSIONS,
    get_current_admin,
    get_current_user,
    hash_password,
    verify_password,
)

router = APIRouter(prefix="/api")


def _check_role(role: str) -> None:
    if role not in ("admin", "user"):
        raise HTTPException(status_code=400, detail="role must be 'admin' or 'user'")


def _check_permissions(perms: list[str]) -> None:
    unknown = set(perms) - set(PERMISSIONS)
    if unknown:
        raise HTTPException(status_code=400, detail=f"unknown permissions: {', '.join(sorted(unknown))}")


def _check_password(password: str) -> None:
    if len(password) < 8:
        raise HTTPException(status_code=400, detail="Passwort muss mindestens 8 Zeichen haben")


def _user_out(row) -> UserOut:
    return UserOut(
        username=row["username"],
        role=row["role"],
        permissions=sorted(json.loads(row["permissions"] or "[]")),
        created_at=row["created_at"],
    )


# --- Self-service ---

@router.get("/me")
def me(user: dict = Depends(get_current_user)):
    return {
        "username": user["username"],
        "role": user["role"],
        "permissions": sorted(user["permissions"]),
    }


@router.post("/me/password", status_code=status.HTTP_204_NO_CONTENT)
def change_own_password(body: PasswordChange, user: dict = Depends(get_current_user)):
    row = database.get_user(user["username"])
    if not verify_password(body.old_password, row["password_hash"]):
        raise HTTPException(status_code=403, detail="Aktuelles Passwort ist falsch")
    _check_password(body.new_password)
    database.set_user_password(user["username"], hash_password(body.new_password))


# --- User management (admin role) ---

@router.get("/users", response_model=list[UserOut])
def get_users(_: dict = Depends(get_current_admin)):
    return [_user_out(r) for r in database.list_users()]


@router.post("/users", response_model=UserOut, status_code=status.HTTP_201_CREATED)
def post_user(body: UserCreate, _: dict = Depends(get_current_admin)):
    username = body.username.strip()
    if not username:
        raise HTTPException(status_code=400, detail="username required")
    _check_role(body.role)
    _check_permissions(body.permissions)
    _check_password(body.password)
    if not database.create_user(username, hash_password(body.password), body.role, body.permissions):
        raise HTTPException(status_code=409, detail="Benutzer existiert bereits")
    return _user_out(database.get_user(username))


@router.patch("/users/{username}", response_model=UserOut)
def patch_user(username: str, body: UserPatch, admin: dict = Depends(get_current_admin)):
    if database.get_user(username) is None:
        raise HTTPException(status_code=404, detail="Benutzer nicht gefunden")
    if body.role is not None:
        _check_role(body.role)
        # An admin cannot demote themselves — prevents locking everyone out.
        if username == admin["username"] and body.role != "admin":
            raise HTTPException(status_code=400, detail="Eigene Admin-Rolle kann nicht entzogen werden")
        database.update_user(username, role=body.role)
    if body.permissions is not None:
        _check_permissions(body.permissions)
        database.update_user(username, permissions=body.permissions)
    if body.password is not None:
        _check_password(body.password)
        database.set_user_password(username, hash_password(body.password))
    return _user_out(database.get_user(username))


@router.delete("/users/{username}", status_code=status.HTTP_204_NO_CONTENT)
def delete_user(username: str, admin: dict = Depends(get_current_admin)):
    if username == admin["username"]:
        raise HTTPException(status_code=400, detail="Eigenes Konto kann nicht gelöscht werden")
    if not database.delete_user(username):
        raise HTTPException(status_code=404, detail="Benutzer nicht gefunden")
