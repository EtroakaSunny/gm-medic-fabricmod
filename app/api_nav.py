"""Admin REST API for the beta street-navigation system (see nav.py)."""
from fastapi import APIRouter, Depends, HTTPException

from .nav import GRID, nav
from .security import get_current_admin

router = APIRouter(prefix="/api/nav")


@router.get("/stats")
def nav_stats(_: str = Depends(get_current_admin)):
    return nav.stats()


@router.get("/graph")
def nav_graph(min_count: int = 1, limit: int = 40_000, _: str = Depends(get_current_admin)):
    return {"grid": GRID, "edges": nav.edges_for_display(min_count, limit)}


@router.get("/route")
def nav_route(fromX: float, fromZ: float, toX: float, toZ: float,
              min_count: int = 2, _: str = Depends(get_current_admin)):
    """Route between two block positions. ``min_count`` skips edges driven
    fewer times (filters one-off detours); it is relaxed to 1 automatically
    if the strict graph finds no route."""
    result = nav.route((fromX, fromZ), (toX, toZ), min_count=min_count)
    if result is None and min_count > 1:
        result = nav.route((fromX, fromZ), (toX, toZ), min_count=1)
    if result is None:
        raise HTTPException(status_code=404, detail="Keine Route gefunden — noch zu wenig Fahrdaten")
    return result
