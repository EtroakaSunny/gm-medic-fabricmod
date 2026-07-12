"""Nearest-free-medic computation in the XZ plane (Minecraft ground plane)."""
import math


def compute_nearest(call: dict, online: dict):
    """Return (medic_username, distance_blocks) or (None, None).

    Considers every online, on-duty medic with a known location — including
    the reporter, so the announcement can name the reporter themselves. The
    call's own caller is skipped: a dead on-duty medic would otherwise always
    win with distance 0. Distance is Euclidean in XZ.
    """
    cx, cz = call.get("x"), call.get("z")
    if cx is None or cz is None or not math.isfinite(cx) or not math.isfinite(cz):
        return None, None

    caller = (call.get("callerName") or "").lower()
    best_name = None
    best_dist = None
    for username, info in online.items():
        if username.lower() == caller:
            continue
        if not info.get("on_duty"):
            continue
        ix, iz = info.get("x"), info.get("z")
        if ix is None or iz is None:
            continue
        dist = math.hypot(ix - cx, iz - cz)
        if best_dist is None or dist < best_dist:
            best_dist = dist
            best_name = username

    if best_name is None:
        return None, None
    return best_name, round(best_dist, 1)


def distance_to_medic(call: dict, online: dict, medic_name: str, max_blocks: float | None = None):
    """Distance in blocks (XZ) from `medic_name`'s last known position to the call.

    Returns None if the medic or the call has no known position, or if
    `max_blocks` is given and the distance exceeds it.
    """
    if not medic_name:
        return None
    cx, cz = call.get("x"), call.get("z")
    if cx is None or cz is None or not math.isfinite(cx) or not math.isfinite(cz):
        return None
    info = online.get(medic_name)
    if info is None:
        return None
    ix, iz = info.get("x"), info.get("z")
    if ix is None or iz is None:
        return None
    dist = math.hypot(ix - cx, iz - cz)
    if max_blocks is not None and dist > max_blocks:
        return None
    return round(dist, 1)
