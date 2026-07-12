"""REST API for the beta street-navigation system (see nav.py)."""
from fastapi import APIRouter, Depends, HTTPException

from .models import NavDrawRequest, NavEraseRequest
from .nav import GRID, nav
from .security import get_current_admin, require_permission

router = APIRouter(prefix="/api/nav", dependencies=[Depends(require_permission("nav"))])


@router.get("/stats")
def nav_stats():
    return nav.stats()


@router.get("/graph")
def nav_graph(min_count: int = 1, limit: int = 40_000):
    return {"grid": GRID, "edges": nav.edges_for_display(min_count, limit)}


@router.get("/route")
def nav_route(fromX: float, fromZ: float, toX: float, toZ: float, min_count: int = 2):
    """Route between two block positions. ``min_count`` skips edges driven
    fewer times (filters one-off detours); it is relaxed to 1 automatically
    if the strict graph finds no route."""
    result = nav.route((fromX, fromZ), (toX, toZ), min_count=min_count)
    if result is None and min_count > 1:
        result = nav.route((fromX, fromZ), (toX, toZ), min_count=1)
    if result is None:
        raise HTTPException(status_code=404, detail="Keine Route gefunden — noch zu wenig Fahrdaten")
    return result


def _points_2d(points: list[list[float]]) -> list[tuple[float, float]]:
    pts = [(float(p[0]), float(p[1])) for p in points if len(p) >= 2]
    if len(pts) > 1000:
        raise HTTPException(status_code=400, detail="Zu viele Punkte (max. 1000)")
    return pts


@router.post("/edit/draw")
def nav_draw(body: NavDrawRequest, _: dict = Depends(get_current_admin)):
    """Hand-draw a street polyline (both directions). Admin only."""
    added = nav.draw(_points_2d(body.points))
    nav.save()
    return {"added": added, **nav.stats()}


@router.post("/edit/erase")
def nav_erase(body: NavEraseRequest, _: dict = Depends(get_current_admin)):
    """Erase all edges near the brush polyline. Admin only."""
    radius = max(1.0, min(64.0, body.radius))
    removed = nav.erase(_points_2d(body.points), radius)
    nav.save()
    return {"removed": removed, **nav.stats()}
