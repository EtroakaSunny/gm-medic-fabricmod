"""Pydantic request/response schemas for the admin REST API."""
from pydantic import BaseModel


class LoginRequest(BaseModel):
    username: str
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class MedicCreate(BaseModel):
    username: str
    display_name: str | None = None


class MedicOut(BaseModel):
    username: str
    display_name: str | None = None
    is_active: bool
    online: bool = False
    on_duty: bool = False
