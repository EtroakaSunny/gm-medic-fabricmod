"""WebSocket endpoint that streams live state to the admin GUI.

Authenticated with the admin JWT passed as a ``?token=`` query parameter
(WebSocket clients cannot set Authorization headers from the browser).
"""
import json

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from .security import verify_ws_token
from .state import state

router = APIRouter()


@router.websocket("/ws/admin")
async def admin_ws(ws: WebSocket, token: str | None = None):
    user = verify_ws_token(token)
    if user is None:
        await ws.close(code=1008)
        return

    await ws.accept()
    # The feed is filtered by the account's view permissions (see state.py);
    # they are fixed for the lifetime of the connection.
    state.register_admin(ws, user["permissions"], user["role"])
    try:
        # Send the full current state immediately on connect.
        await ws.send_text(json.dumps(state.snapshot(user["permissions"], user["role"])))
        while True:
            # No inbound messages expected; keep the socket draining.
            await ws.receive_text()
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        state.unregister_admin(ws)
