"""WebSocket endpoint for mod clients (the medics' Minecraft mod).

Auth is Trust-On-First-Use: the client generates a random UUID token once and
sends it with its Minecraft username. The token binds to that username for 24h
provided the username is on the medic allow-list. Players not on the allow-list
but listed on the public GermanMiner fraction roster are approved automatically
for the lifetime of their connection (no token is bound).
"""
import asyncio
import json
import time

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from . import config, database, roster
from .nav import nav
from .nearest import compute_nearest, distance_to_medic
from .state import safe_float, state

router = APIRouter()


async def _authenticate(token: str | None, username: str | None):
    """Return (ok, reason, expires_at_ms). ``expires None`` = session-only approval."""
    if not token or not username:
        return False, "MALFORMED_AUTH", None

    row = database.get_token(token)
    now_ms = int(time.time() * 1000)

    if row is not None:
        if row["expires_at"] < now_ms:
            # Expired — drop it and fall through to a fresh bind.
            database.delete_token(token)
            row = None
        else:
            if row["username"] != username:
                return False, "TOKEN_MISMATCH", None
            if database.is_active_medic(username):
                expires = database.bind_token(token, username)  # refresh TTL
                return True, None, expires
            if await roster.is_fraction_member(username):
                return True, None, None
            return False, "USERNAME_NOT_IN_MEDIC_DB", None

    # Fresh bind path
    if database.is_active_medic(username):
        expires = database.bind_token(token, username)
        return True, None, expires
    if await roster.is_fraction_member(username):
        return True, None, None
    return False, "USERNAME_NOT_IN_MEDIC_DB", None


async def _send(ws: WebSocket, obj: dict) -> None:
    await ws.send_text(json.dumps(obj))


def _alarm_sync(alarm: dict) -> dict:
    return {
        "type": "ALARM_SYNC",
        "active": True,
        "alarmName": alarm["alarmName"],
        "triggeredAtMs": alarm["triggeredAtMs"],
    }


@router.websocket("/api")
@router.websocket("/ws")
async def mod_ws(ws: WebSocket):
    await ws.accept()
    username: str | None = None
    try:
        # 1) Require an AUTH frame within the timeout window.
        try:
            raw = await asyncio.wait_for(ws.receive_text(), timeout=config.AUTH_TIMEOUT_SECONDS)
        except asyncio.TimeoutError:
            await _send(ws, {"type": "AUTH_FAIL", "reason": "TIMEOUT"})
            await ws.close(code=1008)
            return

        msg = json.loads(raw)
        if msg.get("type") != "AUTH":
            await _send(ws, {"type": "AUTH_FAIL", "reason": "EXPECTED_AUTH"})
            await ws.close(code=1008)
            return

        ok, reason, expires = await _authenticate(msg.get("token"), msg.get("username"))
        if not ok:
            await _send(ws, {"type": "AUTH_FAIL", "reason": reason})
            await ws.close(code=1008)
            return

        username = msg["username"]
        state.register_mod(ws, username)
        await _send(ws, {"type": "AUTH_OK", "username": username, "expiresAt": expires})
        await state.broadcast_admin({"type": "medic_update", "medic": state.medic_view(username)})

        # Sync the new client: hand it every currently open call.
        await _send(ws, {"type": "OPEN_CALLS", "calls": state.open_calls()})

        # ... and the bank alarm, if one is currently active.
        alarm = state.active_alarm()
        if alarm is not None:
            await _send(ws, _alarm_sync(alarm))

        # 2) Main message loop.
        while True:
            raw = await ws.receive_text()
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                continue
            await _handle(ws, username, msg)

    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        if username is not None:
            state.unregister_mod(ws)
            nav.forget_track(username)
            await state.broadcast_admin({"type": "medic_offline", "username": username})


async def _handle(ws: WebSocket, username: str, msg: dict) -> None:
    mtype = msg.get("type")

    if mtype == "PING":
        await _send(ws, {"type": "PONG", "timestamp": msg.get("timestamp")})

    elif mtype == "DUTY_ON":
        state.set_duty(username, True)
        await state.broadcast_admin({"type": "medic_update", "medic": state.medic_view(username)})
        # Re-sync on duty start: open calls may have arrived while off duty.
        await _send(ws, {"type": "OPEN_CALLS", "calls": state.open_calls()})

    elif mtype == "DUTY_OFF":
        state.set_duty(username, False)
        nav.forget_track(username)
        await state.broadcast_admin({"type": "medic_update", "medic": state.medic_view(username)})
        # Off duty now — hand over the active bank alarm (alarms target off-duty clients).
        alarm = state.active_alarm()
        if alarm is not None:
            await _send(ws, _alarm_sync(alarm))

    elif mtype == "LOCATION_UPDATE":
        if state.set_location(username, msg.get("x"), msg.get("y"), msg.get("z")):
            # Street learning only trusts positions the client tagged as sitting
            # in a car (hotbar vehicle item, helicopters excluded) — foot traffic
            # through houses and heli flights must never become streets.
            if msg.get("driving"):
                nav.record(username, msg.get("x"), msg.get("y"), msg.get("z"),
                           msg.get("timestamp") or int(time.time() * 1000))
            else:
                nav.forget_track(username)
            await state.broadcast_admin({"type": "medic_update", "medic": state.medic_view(username)})

    elif mtype == "ALARM_TRIGGERED":
        name = (msg.get("alarmName") or "").strip() or "Unbekannt"
        active = state.active_alarm()
        # Several on-duty medics read the same D-Funk line — only the first counts.
        if active is None or active["alarmName"] != name:
            state.alarm = {
                "alarmName": name,
                "triggeredBy": username,
                "triggeredAtMs": int(time.time() * 1000),
            }
            await state.broadcast_admin({"type": "alarm_update", "alarm": state.alarm})
            await state.broadcast_mods_off_duty(_alarm_sync(state.alarm))

    elif mtype == "ALARM_ENDED":
        if state.active_alarm() is not None:
            state.alarm = None
            await state.broadcast_admin({"type": "alarm_update", "alarm": None})
            await state.broadcast_mods({"type": "ALARM_SYNC", "active": False})

    elif mtype == "CALL_NEW":
        call_id = msg.get("callId")
        if call_id is not None and state.get_call(call_id) is not None:
            # Several on-duty medics' clients detect the same call independently
            # and each report it — only the first report counts, mirroring the
            # ALARM_TRIGGERED dedup below.
            return
        call = {
            "callId": call_id,
            "callerName": msg.get("callerName"),
            "callType": msg.get("callType"),
            "reason": msg.get("reason"),
            "x": safe_float(msg.get("x")),
            "y": safe_float(msg.get("y")),
            "z": safe_float(msg.get("z")),
            "locationName": msg.get("locationName"),
            "deadlineMs": msg.get("deadlineMs"),
            "resolved": bool(msg.get("resolved")),
            "assignedMedic": None,
            "reportedBy": username,
        }
        stored = state.upsert_call(call)
        await state.broadcast_admin({"type": "call_update", "call": stored})
        await state.broadcast_mods({"type": "CALL_SYNC", "call": stored}, exclude=ws)

        # Compute the nearest on-duty medic (reporter included) and announce
        # it to every on-duty mod client, not just the reporter — off-duty
        # medics aren't dispatch candidates, so they shouldn't be told who is.
        nearest, dist = compute_nearest(stored, state.online)
        if nearest is not None:
            stored["suggestedMedic"] = nearest
            await state.broadcast_admin({"type": "call_update", "call": stored})
            await state.broadcast_mods_on_duty({
                "type": "NEAREST_MEDIC",
                "callId": stored["callId"],
                "nearestMedic": nearest,
                "distanceBlocks": dist,
            })

    elif mtype == "CALL_ASSIGNED":
        call = state.get_call(msg.get("callId"))
        if call is not None:
            medic_name = msg.get("medicName")
            call["assignedMedic"] = medic_name
            # Only set when the medic's last known position is close enough to
            # be worth announcing; None otherwise so clients don't hold onto a
            # stale nearby-flag from a previous assignment.
            call["assignedMedicNearbyDistance"] = distance_to_medic(
                call, state.online, medic_name, config.MEDIC_NEARBY_THRESHOLD_BLOCKS
            )
            await state.broadcast_admin({"type": "call_update", "call": call})
            await state.broadcast_mods({"type": "CALL_SYNC", "call": call}, exclude=ws)

    elif mtype == "CALL_RESOLVED":
        call = state.get_call(msg.get("callId"))
        if call is not None:
            call["resolved"] = True
            call["resolveReason"] = msg.get("resolveReason")
            await state.broadcast_admin({"type": "call_update", "call": call})
            await state.broadcast_mods({"type": "CALL_SYNC", "call": call}, exclude=ws)

    elif mtype == "CALL_REJECTED":
        call = state.get_call(msg.get("callId"))
        if call is not None:
            call["rejectedBy"] = msg.get("rejectedBy")
            await state.broadcast_admin({"type": "call_update", "call": call})
            await state.broadcast_mods({"type": "CALL_SYNC", "call": call}, exclude=ws)
