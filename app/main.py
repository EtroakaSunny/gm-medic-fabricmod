"""FastAPI application wiring: routers, static GUI, and startup bootstrap."""
import asyncio
import hashlib
import logging
import os
import re
import secrets
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from fastapi.staticfiles import StaticFiles

from . import config, database
from .api_admin import router as api_router
from .api_nav import router as nav_router
from .api_users import router as users_router
from .map_proxy import router as map_router
from .nav import nav
from .security import hash_password
from .state import state
from .ws_admin import router as admin_ws_router
from .ws_mod import router as mod_ws_router

log = logging.getLogger("gm-medic")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")


def _bootstrap_admin() -> None:
    """Ensure an admin exists. Never hardcodes a password.

    If GM_ADMIN_PASSWORD is set, the admin's password is synced to it on every
    startup (so changing it in the env/compose file always takes effect). If it
    is unset and no admin exists yet, a random password is generated once.
    """
    username = os.environ.get("GM_ADMIN_USER", "admin")
    password = os.environ.get("GM_ADMIN_PASSWORD")

    if password:
        database.upsert_admin(username, hash_password(password))
        log.info("Admin '%s' password synced from GM_ADMIN_PASSWORD", username)
        return

    if database.count_admins() > 0:
        return

    password = secrets.token_urlsafe(12)
    database.upsert_admin(username, hash_password(password))
    log.warning("=" * 60)
    log.warning("Created initial admin account:")
    log.warning("   username: %s", username)
    log.warning("   password: %s", password)
    log.warning("Store this now — set GM_ADMIN_PASSWORD to choose your own.")
    log.warning("=" * 60)


@asynccontextmanager
async def lifespan(app: FastAPI):
    database.init_db()
    _bootstrap_admin()
    nav.load()
    autosave = asyncio.create_task(nav.autosave_loop())
    simplify = asyncio.create_task(nav.simplify_loop())
    history_cleanup = asyncio.create_task(state.history_midnight_loop())
    call_timeout = asyncio.create_task(state.call_timeout_loop())
    blood_cleanup = asyncio.create_task(state.blood_prune_loop())
    log.info("GM-Medic server ready on %s:%s", config.HOST, config.PORT)
    yield
    autosave.cancel()
    simplify.cancel()
    history_cleanup.cancel()
    call_timeout.cancel()
    blood_cleanup.cancel()
    nav.save()


app = FastAPI(title="GM-Medic Server", lifespan=lifespan)


@app.middleware("http")
async def gui_cache_control(request, call_next):
    """GUI assets must revalidate (cheap ETag 304s) instead of being cached —
    Cloudflare's default 4 h edge/browser TTL for js/css otherwise leaves
    clients running a stale app.js against a new index.html after a deploy."""
    response = await call_next(request)
    path = request.url.path
    if path == "/" or path.endswith((".html", ".js", ".css")):
        response.headers["Cache-Control"] = "no-cache"
    return response

_ASSET_REF_RE = re.compile(r'(app\.js|style\.css)(\?v=[^"\']*)?')


def _asset_version() -> str:
    """Hash of app.js + style.css, used to cache-bust their URLs in index.html.
    Cloudflare's default JS/CSS caching ignores the no-cache header above (it
    overrides Cache-Control with its own multi-hour TTL), so the only fetch
    that's guaranteed fresh after a deploy is one for a URL no cache has seen
    before — hence deriving the query string from content instead of a
    manually bumped counter, which is easy to forget (see git history)."""
    h = hashlib.sha256()
    for name in ("app.js", "style.css"):
        h.update((config.STATIC_DIR / name).read_bytes())
    return h.hexdigest()[:10]


@app.get("/", include_in_schema=False)
async def index() -> HTMLResponse:
    html = (config.STATIC_DIR / "index.html").read_text(encoding="utf-8")
    version = _asset_version()
    html = _ASSET_REF_RE.sub(lambda m: f"{m.group(1)}?v={version}", html)
    return HTMLResponse(html)


# REST + WebSocket routes are registered before the static mount so they win.
app.include_router(api_router)
app.include_router(users_router)
app.include_router(nav_router)
app.include_router(mod_ws_router)
app.include_router(admin_ws_router)
app.include_router(map_router)

# Serve the rest of the admin GUI (JS/CSS/vendor assets); "/" is handled above.
app.mount("/", StaticFiles(directory=str(config.STATIC_DIR), html=True), name="static")
