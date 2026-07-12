"""Pydantic request/response schemas for the admin REST API."""
from pydantic import BaseModel


class LoginRequest(BaseModel):
    username: str
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class PasswordChange(BaseModel):
    old_password: str
    new_password: str


class UserCreate(BaseModel):
    username: str
    password: str
    role: str = "user"
    permissions: list[str] = []


class UserPatch(BaseModel):
    role: str | None = None
    permissions: list[str] | None = None
    password: str | None = None


class UserOut(BaseModel):
    username: str
    role: str
    permissions: list[str]
    created_at: int


class NavDrawRequest(BaseModel):
    points: list[list[float]]


class NavEraseRequest(BaseModel):
    points: list[list[float]]
    radius: float = 8.0


class MedicCreate(BaseModel):
    username: str
    display_name: str | None = None


class MedicOut(BaseModel):
    username: str
    display_name: str | None = None
    is_active: bool
    online: bool = False
    on_duty: bool = False
