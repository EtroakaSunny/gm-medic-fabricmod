"""In-memory live state shared between the mod and admin WebSocket endpoints.

Everything here runs inside uvicorn's single asyncio event loop, so no locks
are required. Mutating helpers fan out changes to connected admin GUIs.
"""
import asyncio
import json
import math
import time
from collections import deque

from fastapi import WebSocket

from . import config, database, modversion


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

# Resolved calls are kept in "Verlauf" for a rolling 24h from creation, then
# dropped from memory and the database alike (see prune_history).
HISTORY_RETENTION_MS = 24 * 3600 * 1000
HISTORY_PRUNE_INTERVAL_SECONDS = 300

# How many mod<->server sync messages to keep for the admin "Logs" tab. A
# ring buffer, not the database — restarting the server clears it, same as
# every other piece of live state here.
SYNC_LOG_MAX_ENTRIES = 1000

# Message types excluded from the sync log: PING/PONG is a 30s keep-alive
# and LOCATION_UPDATE fires every ~2s per on-duty medic (see ApiConnection in
# the mod) — logging either would drown out everything else within minutes
# and neither is useful history (positions are already live on the map).
_SYNC_LOG_EXCLUDED_TYPES = {"PING", "PONG", "LOCATION_UPDATE"}

# Cap on a single socket send in a broadcast fan-out. Without this, one
# half-dead connection (dropped Wi-Fi, sleeping phone, a NAT that silently
# dropped the mapping) can sit in ``ws.send_text()`` for a long time before
# the OS ever reports it as gone — and since every broadcast loop below sends
# to one socket at a time, that single stuck send stalls delivery to every
# *other* client behind it in the same broadcast. A generous timeout (well
# above the mod's 30s PING interval) turns a stuck socket into a quick,
# isolated "dead, drop it" instead of a stall felt by everyone.
SEND_TIMEOUT_SECONDS = 5


async def _safe_send(ws: WebSocket, payload: str) -> bool:
    """Sends ``payload`` with a bounded wait; True on success."""
    try:
        await asyncio.wait_for(ws.send_text(payload), timeout=SEND_TIMEOUT_SECONDS)
        return True
    except Exception:
        return False


# Which view permission a GUI connection needs to receive each update type.
# The map draws medics AND calls, so "map" qualifies for both feeds.
_GUI_MSG_PERMS = {
    "medic_update": {"medics", "map"},
    "medic_offline": {"medics", "map"},
    "call_update": {"calls", "map"},
    "call_removed": {"calls", "map"},
    "alarm_update": {"calls", "map"},
    "history_update": {"calls"},
    "history_removed": {"calls"},
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
        # username -> the one mod_ws connection currently "owning" that
        # session, so a stale duplicate connection's disconnect can never be
        # mistaken for the current one's (see register_mod/unregister_mod).
        self._owner_ws: dict[str, WebSocket] = {}
        # connected GUI sockets -> {"permissions": set, "role": str}
        self.admin_ws: dict[WebSocket, dict] = {}
        # ring buffer of {id, ts, username, direction, msgType, message} —
        # every mod<->server sync message, for the admin "Logs" tab
        self.sync_log: deque = deque(maxlen=SYNC_LOG_MAX_ENTRIES)
        self._sync_log_seq = 0

    # --- Mod connection registry ---

    async def register_mod(self, ws: WebSocket, username: str, mod_version: str | None = None) -> None:
        """Registers a newly authenticated mod connection as the one owning
        ``username``'s session.

        A reconnecting client (flaky network, client-side timeout) may open
        its new socket before the server has noticed the old one is dead —
        nothing about ``mod_ws`` (keyed by socket, not username) otherwise
        stops both from being registered at once. Left alone, whichever one's
        disconnect fires last would win and tear down the session, even if
        that's the *stale* connection finally timing out after the live one
        already took over. Evicting the old connection here removes that
        ambiguity outright instead of racing on cleanup.
        """
        old = self._owner_ws.get(username)
        if old is not None and old is not ws:
            self.mod_ws.pop(old, None)
            try:
                await old.close(code=4001)
            except Exception:
                pass
        self._owner_ws[username] = ws
        self.mod_ws[ws] = username
        info = self.online.setdefault(
            username, {"x": None, "y": None, "z": None, "on_duty": False, "last_seen": _now_ms()}
        )
        info["last_seen"] = _now_ms()
        info["mod_version"] = mod_version

    def unregister_mod(self, ws: WebSocket) -> str | None:
        """Unregisters ``ws``. Returns the username if ``ws`` was that user's
        *current* connection (a genuine disconnect the caller should act on),
        or None if it was a stale/superseded connection whose cleanup arrived
        after a newer one already replaced it — in which case the live
        session must be left alone."""
        username = self.mod_ws.pop(ws, None)
        if username is None:
            return None
        if self._owner_ws.get(username) is not ws:
            return None
        self._owner_ws.pop(username, None)
        self.online.pop(username, None)
        return username

    # --- Sync log (admin "Logs" tab) ---

    async def record_sync(self, username: str | None, direction: str, message: dict) -> None:
        """Append one mod<->server message to the log and push it to admin GUIs.

        ``direction`` is "out" (server -> mod) or "in" (mod -> server).
        Messages without a resolved username (e.g. a rejected AUTH attempt)
        aren't logged — there is no client row to attach them to yet.
        """
        if username is None:
            return
        msg_type = message.get("type")
        if msg_type in _SYNC_LOG_EXCLUDED_TYPES:
            return
        self._sync_log_seq += 1
        entry = {
            "id": self._sync_log_seq,
            "ts": _now_ms(),
            "username": username,
            "direction": direction,
            "msgType": msg_type,
            # Never log the TOFU auth token, even though AUTH itself is
            # handled before a username is known and so never reaches here.
            "message": {k: v for k, v in message.items() if k != "token"},
        }
        self.sync_log.append(entry)
        await self.broadcast_admin_role({"type": "sync_log", "entry": entry}, role="admin")

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
        database.save_call(stored)
        return stored

    def persist_call(self, call: dict) -> None:
        """Writes a call's current in-memory contents to disk. Needed only
        for mutations applied in place (e.g. CALL_ASSIGNED setting
        ``assignedMedic`` on a dict already in ``self.calls``) — upsert_call
        and move_call_to_history persist on their own."""
        database.save_call(call)

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

        Keeps a rolling 24h record (see prune_history) for the admin GUI
        instead of the call just vanishing from "Aktive Einsätze" the moment
        it's resolved.
        """
        call = self.calls.pop(call_id, None)
        if call is not None:
            self.history[call_id] = call
            database.save_call(call)
        return call

    def load_persisted_calls(self) -> None:
        """Restores active calls and the last HISTORY_RETENTION_MS of
        resolved calls from disk. Called once at startup so a restart
        (redeploy, crash) doesn't wipe the dispatch board — only these
        in-memory dicts do that, the database doesn't."""
        cutoff = _now_ms() - HISTORY_RETENTION_MS
        for call in database.load_calls():
            call_id = call.get("callId")
            if call_id is None:
                continue
            if call.get("resolved"):
                if (call.get("timestamp") or 0) >= cutoff:
                    self.history[call_id] = call
            else:
                self.calls[call_id] = call
        database.prune_calls_older_than(cutoff)

    def prune_history(self) -> list[str]:
        """Drops resolved calls older than HISTORY_RETENTION_MS from memory
        and disk. Returns the callIds removed."""
        cutoff = _now_ms() - HISTORY_RETENTION_MS
        expired = [cid for cid, c in self.history.items() if (c.get("timestamp") or 0) < cutoff]
        for cid in expired:
            del self.history[cid]
        if expired:
            database.prune_calls_older_than(cutoff)
        return expired

    async def history_prune_loop(self) -> None:
        """Periodically drops history entries once they age past 24h."""
        while True:
            await asyncio.sleep(HISTORY_PRUNE_INTERVAL_SECONDS)
            for call_id in self.prune_history():
                await self.broadcast_admin({"type": "history_removed", "callId": call_id})

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

    def snapshot(self, permissions: set | None = None, role: str | None = None) -> dict:
        """Full state for one GUI connection, filtered by its permissions
        (None = everything). The sync log is role-gated separately — it's an
        admin-only view, not something a permission checkbox can grant."""
        def allowed(views: set) -> bool:
            return permissions is None or bool(permissions & views)

        return {
            "type": "snapshot",
            "medics": [self.medic_view(u) for u in self.online] if allowed({"medics", "map"}) else [],
            "calls": list(self.calls.values()) if allowed({"calls", "map"}) else [],
            "history": list(self.history.values()) if allowed({"calls"}) else [],
            "alarm": self.active_alarm() if allowed({"calls", "map"}) else None,
            "syncLog": list(self.sync_log) if role == "admin" else [],
        }

    # --- GUI broadcast ---

    def register_admin(self, ws: WebSocket, permissions: set, role: str) -> None:
        self.admin_ws[ws] = {"permissions": set(permissions), "role": role}

    def unregister_admin(self, ws: WebSocket) -> None:
        self.admin_ws.pop(ws, None)

    async def broadcast_admin(self, message: dict) -> None:
        if not self.admin_ws:
            return
        required = _GUI_MSG_PERMS.get(message.get("type"))
        payload = json.dumps(message)
        dead = []
        for ws, info in list(self.admin_ws.items()):
            if required is not None and not (info["permissions"] & required):
                continue
            if not await _safe_send(ws, payload):
                dead.append(ws)
        for ws in dead:
            self.admin_ws.pop(ws, None)

    async def broadcast_admin_role(self, message: dict, role: str) -> None:
        """Like ``broadcast_admin``, but restricted to GUI accounts holding
        exactly this role — for admin-only views (the sync log) that a
        regular account's view permissions can't unlock."""
        if not self.admin_ws:
            return
        payload = json.dumps(message)
        dead = []
        for ws, info in list(self.admin_ws.items()):
            if info["role"] != role:
                continue
            if not await _safe_send(ws, payload):
                dead.append(ws)
        for ws in dead:
            self.admin_ws.pop(ws, None)

    async def _reap_dead_mod(self, ws: WebSocket) -> None:
        """Cleans up a mod socket a broadcast just found dead, going through
        ``unregister_mod`` so a stale duplicate connection (see register_mod)
        can never be mistaken for the one currently owning that username's
        session, and notifies admin GUIs only for a genuine disconnect."""
        username = self.unregister_mod(ws)
        if username is not None:
            await self.broadcast_admin({"type": "medic_offline", "username": username})

    async def broadcast_mods(self, message: dict, exclude: WebSocket | None = None) -> None:
        """Fan a message out to all connected mod clients (optionally minus the sender)."""
        if not self.mod_ws:
            return
        payload = json.dumps(message)
        dead = []
        for ws, username in list(self.mod_ws.items()):
            if ws is exclude:
                continue
            if not await _safe_send(ws, payload):
                dead.append(ws)
                continue
            await self.record_sync(username, "out", message)
        for ws in dead:
            await self._reap_dead_mod(ws)

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
            if not await _safe_send(ws, payload):
                dead.append(ws)
                continue
            await self.record_sync(username, "out", message)
        for ws in dead:
            await self._reap_dead_mod(ws)

    async def send_to_usernames(self, usernames: set[str], message: dict) -> list[str]:
        """Sends a message to only the given (connected) mod clients — the admin
        GUI's "send test message" tool. Returns the usernames actually reached,
        so the caller can tell a valid-but-offline target from a typo."""
        if not self.mod_ws or not usernames:
            return []
        payload = json.dumps(message)
        sent = []
        dead = []
        for ws, username in list(self.mod_ws.items()):
            if username not in usernames:
                continue
            if not await _safe_send(ws, payload):
                dead.append(ws)
                continue
            await self.record_sync(username, "out", message)
            sent.append(username)
        for ws in dead:
            await self._reap_dead_mod(ws)
        return sent

    async def disconnect_mod(self, username: str, code: int = 4000) -> bool:
        """Force-closes one mod client's connection (the admin GUI's "reset
        connection" tool). ``mod_ws`` unregisters itself the normal way, via the
        WebSocketDisconnect the close triggers in the mod_ws handler's loop."""
        for ws, u in list(self.mod_ws.items()):
            if u == username:
                try:
                    await ws.close(code=code)
                except Exception:
                    pass
                return True
        return False

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
            if not await _safe_send(ws, payload):
                dead.append(ws)
                continue
            await self.record_sync(username, "out", message)
        for ws in dead:
            await self._reap_dead_mod(ws)


state = LiveState()
