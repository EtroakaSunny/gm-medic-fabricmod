"""REST API: login plus medic/token management.

Viewing routes require the matching view permission; mutations require the
admin role. See ``api_users.py`` for accounts and ``security.py`` for roles.
"""
from fastapi import APIRouter, Depends, HTTPException, status

from . import database
from .models import LoginRequest, MedicCreate, MedicOut, TokenResponse
from .security import create_access_token, get_current_admin, require_permission, verify_password
from .state import state

router = APIRouter(prefix="/api")


@router.post("/auth/login", response_model=TokenResponse)
def login(body: LoginRequest):
    user = database.get_user(body.username)
    if user is None or not verify_password(body.password, user["password_hash"]):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid credentials",
        )
    token, expires_in = create_access_token(body.username)
    return TokenResponse(access_token=token, expires_in=expires_in)


@router.get("/medics", response_model=list[MedicOut])
def get_medics(_: dict = Depends(require_permission("medics"))):
    result = []
    for m in database.list_medics():
        online_info = state.online.get(m["username"])
        result.append(MedicOut(
            username=m["username"],
            display_name=m["display_name"],
            is_active=bool(m["is_active"]),
            online=online_info is not None,
            on_duty=bool(online_info["on_duty"]) if online_info else False,
        ))
    return result


@router.post("/medics", response_model=MedicOut, status_code=status.HTTP_201_CREATED)
def post_medic(body: MedicCreate, _: dict = Depends(get_current_admin)):
    if not body.username.strip():
        raise HTTPException(status_code=400, detail="username required")
    database.add_medic(body.username.strip(), body.display_name)
    online_info = state.online.get(body.username)
    return MedicOut(
        username=body.username.strip(),
        display_name=body.display_name,
        is_active=True,
        online=online_info is not None,
        on_duty=bool(online_info["on_duty"]) if online_info else False,
    )


@router.delete("/medics/{username}", status_code=status.HTTP_204_NO_CONTENT)
def delete_medic(username: str, _: dict = Depends(get_current_admin)):
    if not database.remove_medic(username):
        raise HTTPException(status_code=404, detail="medic not found")
    # Revoke any bound tokens so the client must re-auth (and will now fail).
    database.delete_tokens_for_user(username)


@router.delete("/tokens/{username}", status_code=status.HTTP_204_NO_CONTENT)
def revoke_tokens(username: str, _: dict = Depends(get_current_admin)):
    database.delete_tokens_for_user(username)


@router.get("/calls")
def get_calls(_: dict = Depends(require_permission("calls"))):
    return list(state.calls.values())


@router.get("/online")
def get_online(_: dict = Depends(require_permission("medics"))):
    return [state.medic_view(u) for u in state.online]
