"""Admin REST API: login plus medic/token management.

All routes except ``/api/auth/login`` require a valid admin JWT.
"""
from fastapi import APIRouter, Depends, HTTPException, status

from . import database
from .models import LoginRequest, MedicCreate, MedicOut, TokenResponse
from .security import create_access_token, get_current_admin, verify_password
from .state import state

router = APIRouter(prefix="/api")


@router.post("/auth/login", response_model=TokenResponse)
def login(body: LoginRequest):
    admin = database.get_admin(body.username)
    if admin is None or not verify_password(body.password, admin["password_hash"]):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid credentials",
        )
    token, expires_in = create_access_token(body.username)
    return TokenResponse(access_token=token, expires_in=expires_in)


@router.get("/medics", response_model=list[MedicOut])
def get_medics(_: str = Depends(get_current_admin)):
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
def post_medic(body: MedicCreate, _: str = Depends(get_current_admin)):
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
def delete_medic(username: str, _: str = Depends(get_current_admin)):
    if not database.remove_medic(username):
        raise HTTPException(status_code=404, detail="medic not found")
    # Revoke any bound tokens so the client must re-auth (and will now fail).
    database.delete_tokens_for_user(username)


@router.delete("/tokens/{username}", status_code=status.HTTP_204_NO_CONTENT)
def revoke_tokens(username: str, _: str = Depends(get_current_admin)):
    database.delete_tokens_for_user(username)


@router.get("/calls")
def get_calls(_: str = Depends(get_current_admin)):
    return list(state.calls.values())


@router.get("/online")
def get_online(_: str = Depends(get_current_admin)):
    return [state.medic_view(u) for u in state.online]
