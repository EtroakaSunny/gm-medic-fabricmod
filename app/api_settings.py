"""Admin-only REST API for server-wide settings.

Currently just the published mod version and its download link (see
modversion.py) — surfaced in the GUI's "Administration" tab so an admin can
change them without editing the server's env and redeploying.
"""
from fastapi import APIRouter, Depends

from . import database, modversion
from .models import ModUpdateSettings
from .security import get_current_admin

router = APIRouter(prefix="/api")


def _current() -> ModUpdateSettings:
    return ModUpdateSettings(
        latest_version=modversion.latest_version(),
        download_url=modversion.download_url(),
    )


@router.get("/settings/mod-update", response_model=ModUpdateSettings)
def get_mod_update_settings(_: dict = Depends(get_current_admin)):
    return _current()


@router.put("/settings/mod-update", response_model=ModUpdateSettings)
def put_mod_update_settings(body: ModUpdateSettings, _: dict = Depends(get_current_admin)):
    database.set_setting("latest_mod_version", body.latest_version.strip())
    database.set_setting("mod_download_url", body.download_url.strip())
    return _current()
