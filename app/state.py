"""In-memory live state shared between the mod and admin WebSocket endpoints.

Everything here runs inside uvicorn's single asyncio event loop, so no locks
are required. Mutating helpers fan out changes to connected admin GUIs.
"""
import asyncio
import json
import math
import time
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from fastapi import WebSocket

from . import config, modversion


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

# Safety net: auto-resolve an open call (E-Call or DEATH) after this long.
CALL_TIMEOUT_MS = int(config.CALL_TIMEOUT_MINUTES * 60 * 1000)
CALL_TIMEOUT_CHECK_INTERVAL_SECONDS = 60


def _seconds_until_next_midnight(tz_name: str) -> float:
    tz = ZoneInfo(tz_name)
    now = datetime.now(tz)
    tomorrow = (now + timedelta(days=1)).date()
    next_midnight = datetime(tomorrow.year, tomorrow.month, tomorrow.day, tzinfo=tz)
    return (next_midnight - now).total_seconds()


# Which view permission a GUI connection needs to receive each update type.
# The map draws medics AND calls, so "map" qualifies for both feeds.
_GUI_MSG_PERMS = {
    "medic_update": {"medics", "map"},
    "medic_offline": {"medics", "map"},
    "call_update": {"calls", "map"},
    "call_removed": {"calls", "map"},
    "alarm_update": {"calls", "map"},
    "history_update": {"calls"},
    "history_cleared": {"calls"},
}


class LiveState:
    def __init__(self) -> None:
        # username -> {x, y, z, on_duty, last_seen}
        self.online: dict[str, dict] = {}
        # callId -> call dict (only calls that are still open)
        self.calls: dict[str, dict] = {}
        # callId -> call dict, for resolved calls; cleared every local midnight
        self.history: dict[str, dict] = {}
        # lowercased player name -> blood-donation record, dropped once the
        # cooldown has run out (see prune_blood_draws)
        self.blood_draws: dict[str, dict] = {}
        # active bank alarm: {alarmName, triggeredBy, triggeredAtMs} or None
        self.alarm: dict | None = None
        # mod websocket -> username
        self.mod_ws: dict[WebSocket, str] = {}
        # connected GUI sockets -> that account's view-permission set
        self.admin_ws: dict[WebSocket, set] = {}

    # --- Mod connection registry ---

    def register_mod(self, ws: WebSocket, username: str, mod_version: str | None = None) -> None:
        self.mod_ws[ws] = username
        info = self.online.setdefault(
            username, {"x": None, "y": None, "z": None, "on_duty": False, "last_seen": _now_ms()}
        )
        info["last_seen"] = _now_ms()
        info["mod_version"] = mod_version

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

    def find_open_call_by_caller(self, caller_name, call_type) -> dict | None:
        """Find an open call already reported for this caller and call type.

        Each on-duty medic's client mints its own random callId when it parses a
        transmission from chat, so several clients reporting the same real call
        each send a different callId — callId alone can't dedupe them. Match on
        caller name + type instead, mirroring the mod's own duplicate-transmission
        filter (EmergencyCallManager.isDuplicateTransmission).
        """
        key = (caller_name or "").strip().lower()
        if not key:
            return None
        for call in self.calls.values():
            if call.get("resolved"):
                continue
            existing_key = (call.get("callerName") or "").strip().lower()
            if existing_key == key and call.get("callType") == call_type:
                return call
        return None

    def open_calls(self) -> list[dict]:
        return [c for c in self.calls.values() if not c.get("resolved")]

    def move_call_to_history(self, call_id: str) -> dict | None:
        """Pop a resolved call out of the active set and file it under history.

        Keeps a same-day record for the admin GUI instead of the call just
        vanishing from "Aktive Einsätze" the moment it's resolved.
        """
        call = self.calls.pop(call_id, None)
        if call is not None:
            self.history[call_id] = call
        return call

    def clear_history(self) -> list[dict]:
        cleared = list(self.history.values())
        self.history.clear()
        return cleared

    async def history_midnight_loop(self) -> None:
        """Clears the resolved-call history every day at local midnight."""
        while True:
            await asyncio.sleep(_seconds_until_next_midnight(config.HISTORY_TIMEZONE))
            if self.clear_history():
                await self.broadcast_admin({"type": "history_cleared"})

    def expire_stale_calls(self) -> list[dict]:
        """Auto-resolve open calls (E-Call or DEATH) that have sat unhandled
        past the timeout, filing them into history as "erledigt".

        Calls without a `timestamp` (shouldn't happen for anything created
        after this was added) are left alone rather than guessed at.
        """
        now = _now_ms()
        expired = []
        for call in list(self.calls.values()):
            if call.get("resolved"):
                continue
            created = call.get("timestamp")
            if created is None or now - created < CALL_TIMEOUT_MS:
                continue
            call["resolved"] = True
            call["resolveReason"] = "timeout"
            self.move_call_to_history(call["callId"])
            expired.append(call)
        return expired

    async def call_timeout_loop(self) -> None:
        """Periodically auto-resolves calls that have been open too long."""
        while True:
            await asyncio.sleep(CALL_TIMEOUT_CHECK_INTERVAL_SECONDS)
            for call in self.expire_stale_calls():
                await self.broadcast_mods({"type": "CALL_SYNC", "call": call})
                await self.broadcast_admin({"type": "call_removed", "callId": call["callId"]})
                await self.broadcast_admin({"type": "history_update", "call": call})

    # --- Blood donations ---

    @staticmethod
    def _blood_key(player_name) -> str | None:
        key = (player_name or "").strip().lower()
        return key or None

    def prune_blood_draws(self) -> int:
        """Forget every donation whose cooldown has run out. Returns the count.

        The record *is* the cooldown — there is no separate "has donated" flag —
        so dropping it leaves no trace of the player at all, which is the point:
        nothing is kept longer than ``config.BLOOD_COOLDOWN_SECONDS``.
        """
        now = _now_ms()
        expired = [k for k, d in self.blood_draws.items() if d["readyAtMs"] <= now]
        for key in expired:
            del self.blood_draws[key]
        return len(expired)

    def get_blood_draw(self, player_name) -> dict | None:
        """The player's live donation record, or None if they may donate now."""
        key = self._blood_key(player_name)
        if key is None:
            return None
        draw = self.blood_draws.get(key)
        if draw is None:
            return None
        if draw["readyAtMs"] <= _now_ms():
            # Expired between sweeps — drop it here so reads never see stale data.
            del self.blood_draws[key]
            return None
        return draw

    def record_blood_draw(self, player_name, medic_name) -> dict | None:
        """Start a player's cooldown; None if one is already running.

        Refusing to overwrite a live record is what stops the cooldown from
        being pushed further out when the same donation is reported twice (a
        re-parsed chat line, or a second medic reporting the same event).
        """
        if self._blood_key(player_name) is None:
            return None
        if self.get_blood_draw(player_name) is not None:
            return None
        now = _now_ms()
        draw = {
            "playerName": (player_name or "").strip(),
            "medicName": medic_name,
            "drawnAtMs": now,
            "readyAtMs": now + config.BLOOD_COOLDOWN_SECONDS * 1000,
        }
        self.blood_draws[self._blood_key(player_name)] = draw
        return draw

    def blood_status(self, player_name) -> dict:
        """Donation status payload for one player (no message ``type`` field)."""
        draw = self.get_blood_draw(player_name)
        if draw is None:
            return {
                "playerName": (player_name or "").strip(),
                "canDonate": True,
                "readyAtMs": None,
                "remainingSeconds": 0,
            }
        remaining_ms = draw["readyAtMs"] - _now_ms()
        return {
            "playerName": draw["playerName"],
            "canDonate": False,
            "readyAtMs": draw["readyAtMs"],
            "remainingSeconds": max(0, -(-remaining_ms // 1000)),  # ceil
            "medicName": draw.get("medicName"),
        }

    def blood_draws_view(self) -> list[dict]:
        """All live donation records, for syncing a freshly connected client."""
        self.prune_blood_draws()
        return [dict(d) for d in self.blood_draws.values()]

    async def blood_prune_loop(self) -> None:
        """Sweeps expired donations so they are dropped even without a reader."""
        while True:
            await asyncio.sleep(config.BLOOD_PRUNE_INTERVAL_SECONDS)
            dropped = self.prune_blood_draws()
            if dropped:
                log.info("Blood cooldown expired for %d player(s)", dropped)

    def active_alarm(self) -> dict | None:
        """The current bank alarm; drops it if the end message was missed."""
        if self.alarm is not None and _now_ms() - self.alarm["triggeredAtMs"] > ALARM_MAX_AGE_MS:
            self.alarm = None
        return self.alarm

    def medic_view(self, username: str) -> dict:
        info = self.online.get(username, {})
        mod_version, _ = modversion.split_version(info.get("mod_version"))
        return {
            "username": username,
            "x": info.get("x"),
            "y": info.get("y"),
            "z": info.get("z"),
            "on_duty": info.get("on_duty", False),
            "last_seen": info.get("last_seen"),
            "mod_version": mod_version or None,
            "mod_outdated": modversion.is_outdated(mod_version, modversion.latest_version()),
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
            "history": list(self.history.values()) if allowed({"calls"}) else [],
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
