"""Nearest-free-medic computation in the XZ plane (Minecraft ground plane)."""
import math


def compute_nearest(call: dict, online: dict, reporter: str | None):
    """Return (medic_username, distance_blocks) or (None, None).

    A candidate medic must be: online, on duty, not the reporter, and not
    already assigned to another unresolved call. Distance is Euclidean in XZ.
    """
    cx, cz = call.get("x"), call.get("z")
    if cx is None or cz is None or not math.isfinite(cx) or not math.isfinite(cz):
        return None, None

    best_name = None
    best_dist = None
    for username, info in online.items():
        if username == reporter:
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
