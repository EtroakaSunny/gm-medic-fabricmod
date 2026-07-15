"""In-memory live state shared between the mod and admin WebSocket endpoints.

Everything here runs inside uvicorn's single asyncio event loop, so no locks
are required. Mutating helpers fan out changes to connected admin GUIs.
"""
import json
import math
import time

from fastapi import WebSocket


def safe_float(v):
    """Coerce a JSON number to a finite float, or None (NaN/Inf -> None)."""
    if v is None:
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


def _now_ms() -> int:
    return int(time.time() * 1000)


# Safety net: never keep a bank alarm active longer than this.
ALARM_MAX_AGE_MS = 30 * 60 * 1000

# Which view permission a GUI connection needs to receive each update type.
# The map draws medics AND calls, so "map" qualifies for both feeds.
_GUI_MSG_PERMS = {
    "medic_update": {"medics", "map"},
    "medic_offline": {"medics", "map"},
    "call_update": {"calls", "map"},
    "call_removed": {"calls", "map"},
    "alarm_update": {"calls", "map"},
}


class LiveState:
    def __init__(self) -> None:
        # username -> {x, y, z, on_duty, last_seen}
        self.online: dict[str, dict] = {}
        # callId -> call dict
        self.calls: dict[str, dict] = {}
        # active bank alarm: {alarmName, triggeredBy, triggeredAtMs} or None
        self.alarm: dict | None = None
        # mod websocket -> username
        self.mod_ws: dict[WebSocket, str] = {}
        # connected GUI sockets -> that account's view-permission set
        self.admin_ws: dict[WebSocket, set] = {}

    # --- Mod connection registry ---

    def register_mod(self, ws: WebSocket, username: str) -> None:
        self.mod_ws[ws] = username
        info = self.online.setdefault(
            username, {"x": None, "y": None, "z": None, "on_duty": False, "last_seen": _now_ms()}
        )
        info["last_seen"] = _now_ms()

    def unregister_mod(self, ws: WebSocket) -> str | None:
        username = self.mod_ws.pop(ws, None)
        if username:
            self.online.pop(username, None)
        return username

    # --- Mutations from mod messages ---

    def set_duty(self, username: str, on_duty: bool) -> None:
        info = self.online.setdefault(
            username, {"x": None, "y": None, "z": None, "on_duty": False, "last_seen": _now_ms()}
        )
        info["on_duty"] = on_duty
        if not on_duty:
            # Off duty means no position tracking — drop the last known location.
            info["x"] = info["y"] = info["z"] = None
        info["last_seen"] = _now_ms()

    def set_location(self, username: str, x, y, z) -> bool:
        """Store a position update; returns False (ignored) for off-duty medics.

        Only on-duty medics are tracked. The current mod already sends locations
        on duty only; this also drops updates from older mod versions.
        """
        info = self.online.setdefault(
            username, {"x": None, "y": None, "z": None, "on_duty": False, "last_seen": _now_ms()}
        )
        info["last_seen"] = _now_ms()
        if not info["on_duty"]:
            return False
        info["x"] = safe_float(x)
        info["y"] = safe_float(y)
        info["z"] = safe_float(z)
        return True

    def upsert_call(self, call: dict) -> dict:
        call_id = call.get("callId")
        stored = self.calls.get(call_id, {})
        stored.update(call)
        self.calls[call_id] = stored
        return stored

    def get_call(self, call_id: str) -> dict | None:
        return self.calls.get(call_id)

    def open_calls(self) -> list[dict]:
        return [c for c in self.calls.values() if not c.get("resolved")]

    def active_alarm(self) -> dict | None:
        """The current bank alarm; drops it if the end message was missed."""
        if self.alarm is not None and _now_ms() - self.alarm["triggeredAtMs"] > ALARM_MAX_AGE_MS:
            self.alarm = None
        return self.alarm

    def medic_view(self, username: str) -> dict:
        info = self.online.get(username, {})
        return {
            "username": username,
            "x": info.get("x"),
            "y": info.get("y"),
            "z": info.get("z"),
            "on_duty": info.get("on_duty", False),
            "last_seen": info.get("last_seen"),
        }

    def snapshot(self, permissions: set | None = None) -> dict:
        """Full state for one GUI connection, filtered by its permissions
        (None = everything)."""
        def allowed(views: set) -> bool:
            return permissions is None or bool(permissions & views)

        return {
            "type": "snapshot",
            "medics": [self.medic_view(u) for u in self.online] if allowed({"medics", "map"}) else [],
            "calls": list(self.calls.values()) if allowed({"calls", "map"}) else [],
            "alarm": self.active_alarm() if allowed({"calls", "map"}) else None,
        }

    # --- GUI broadcast ---

    def register_admin(self, ws: WebSocket, permissions: set) -> None:
        self.admin_ws[ws] = set(permissions)

    def unregister_admin(self, ws: WebSocket) -> None:
        self.admin_ws.pop(ws, None)

    async def broadcast_admin(self, message: dict) -> None:
        if not self.admin_ws:
            return
        required = _GUI_MSG_PERMS.get(message.get("type"))
        payload = json.dumps(message)
        dead = []
        for ws, perms in list(self.admin_ws.items()):
            if required is not None and not (perms & required):
                continue
            try:
                await ws.send_text(payload)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.admin_ws.pop(ws, None)

    async def broadcast_mods(self, message: dict, exclude: WebSocket | None = None) -> None:
        """Fan a message out to all connected mod clients (optionally minus the sender)."""
        if not self.mod_ws:
            return
        payload = json.dumps(message)
        dead = []
        for ws in list(self.mod_ws):
            if ws is exclude:
                continue
            try:
                await ws.send_text(payload)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.mod_ws.pop(ws, None)

    async def broadcast_mods_off_duty(self, message: dict) -> None:
        """Fan a message out to connected mod clients whose medic is NOT on duty."""
        if not self.mod_ws:
            return
        payload = json.dumps(message)
        dead = []
        for ws, username in list(self.mod_ws.items()):
            info = self.online.get(username)
            if info is not None and info.get("on_duty"):
                continue
            try:
                await ws.send_text(payload)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.mod_ws.pop(ws, None)

    async def broadcast_mods_on_duty(self, message: dict, exclude: WebSocket | None = None) -> None:
        """Fan a message out to connected mod clients whose medic IS on duty."""
        if not self.mod_ws:
            return
        payload = json.dumps(message)
        dead = []
        for ws, username in list(self.mod_ws.items()):
            if ws is exclude:
                continue
            info = self.online.get(username)
            if info is None or not info.get("on_duty"):
                continue
            try:
                await ws.send_text(payload)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.mod_ws.pop(ws, None)


state = LiveState()
