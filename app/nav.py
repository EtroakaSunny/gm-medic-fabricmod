"""Street-network learning from medic position traces, plus routing (beta).

On-duty medics stream their position every ~2 s, tagged ``driving`` while the
client detects them sitting in a car (vehicle control item in the hotbar;
helicopters carry a different item and are excluded). Consecutive driving
samples of one medic form a movement segment; segments are rasterised onto a
coarse grid (``GRID`` blocks per cell, ``Y_LAYER`` blocks per vertical layer)
and stored as directed edges carrying traversal count and average speed. Over
time the drivable street network emerges from usage alone — no block data
needed: streets are what gets driven on, and one-way roads are learned
naturally from direction. The vertical layer keeps a tunnel and the road
above it apart (no phantom junction where they cross), while ramps still
connect layers because their samples span both.

Routing is plain Dijkstra over travel time (edge length / learned average
speed), which on this graph size (tens of thousands of edges) takes
milliseconds. The graph persists to ``data/nav-graph.json``.
"""
import asyncio
import heapq
import json
import logging
import math
import os
import time

from . import config

log = logging.getLogger("gm-medic.nav")

GRID = 4                  # blocks per grid cell (streets are ~5-7 wide)
# Vertical layer height. Separates a tunnel from the road above it (they are
# typically 6+ blocks apart) without splitting ordinary slopes into layers.
Y_LAYER = 10
MAX_SPEED = 40.0          # blocks/s — anything faster is a teleport/respawn
# The client only tags samples as ``driving`` while seated in a car (hotbar
# vehicle item; helicopters excluded), so this is just a sanity floor against
# creeping/parking noise — not the house/foot-traffic filter anymore.
MIN_RECORD_SPEED = float(os.environ.get("GM_NAV_MIN_SPEED", "3.0"))
MIN_MOVE = 0.8            # blocks — anything slower is standing around
MIN_DT_MS = 400           # duplicate/burst packets
MAX_DT_MS = 10_000        # gap (lag, off duty, reconnect) — breaks the track
MAX_DY = 12.0             # vertical jump — elevator/teleport, breaks the track
SNAP_RADIUS = 96.0        # blocks — max distance from a query point to the net
MAX_EDGES = 250_000       # prune threshold
# Hand-drawn edges get this traversal count so the router's min_count=2
# preference keeps them and pruning never drops them as single-use noise.
EDIT_COUNT = 5
PRUNE_AGE_MS = 7 * 24 * 3600 * 1000

GRAPH_PATH = config.DATA_DIR / "nav-graph.json"
AUTOSAVE_SECONDS = 300


def _cell(x: float, z: float, y: float) -> tuple[int, int, int]:
    return (math.floor(x / GRID), math.floor(z / GRID), math.floor(y / Y_LAYER))


def _center(cell: tuple[int, int, int]) -> tuple[float, float]:
    """2D centre of a cell — the vertical layer only disambiguates nodes."""
    return (cell[0] * GRID + GRID / 2, cell[1] * GRID + GRID / 2)


class NavGraph:
    def __init__(self) -> None:
        # directed adjacency: cell -> {neighbour cell: [count, speed_sum, last_ms]}
        self.adj: dict[tuple[int, int, int], dict[tuple[int, int, int], list]] = {}
        self.edge_count = 0
        self.segments = 0  # accepted movement segments (lifetime)
        self._last: dict[str, tuple[float, float, float, int]] = {}  # user -> x,y,z,t
        self._dirty = False

    # --- Collection ---

    def record(self, username: str, x, y, z, t_ms) -> None:
        try:
            x, y, z = float(x), float(y), float(z)
            t_ms = int(t_ms)
        except (TypeError, ValueError):
            return
        if not (math.isfinite(x) and math.isfinite(y) and math.isfinite(z)):
            return

        prev = self._last.get(username)
        self._last[username] = (x, y, z, t_ms)
        if prev is None:
            return
        px, py, pz, pt = prev

        dt = t_ms - pt
        if dt < MIN_DT_MS:
            self._last[username] = prev  # keep the older anchor for tiny bursts
            return
        if dt > MAX_DT_MS or abs(y - py) > MAX_DY:
            return  # track break — new anchor already stored

        dist = math.hypot(x - px, z - pz)
        speed = dist / (dt / 1000.0)
        if dist < MIN_MOVE or speed > MAX_SPEED:
            return
        if speed < MIN_RECORD_SPEED:
            return  # creeping/parking — too slow to be street driving

        # Rasterise the segment: sample along the line finely enough that
        # consecutive cells are always neighbours, then connect them. The
        # y interpolation lets ramps bridge vertical layers naturally.
        steps = max(1, math.ceil(dist / (GRID * 0.45)))
        cells = []
        for i in range(steps + 1):
            f = i / steps
            c = _cell(px + (x - px) * f, pz + (z - pz) * f, py + (y - py) * f)
            if not cells or cells[-1] != c:
                cells.append(c)
        if len(cells) < 2:
            return

        now_ms = int(time.time() * 1000)
        for a, b in zip(cells, cells[1:]):
            edges = self.adj.setdefault(a, {})
            stat = edges.get(b)
            if stat is None:
                edges[b] = [1, speed, now_ms]
                self.edge_count += 1
            else:
                stat[0] += 1
                stat[1] += speed
                stat[2] = now_ms
        self.segments += 1
        self._dirty = True

    def forget_track(self, username: str) -> None:
        """Break the movement track (duty off / disconnect)."""
        self._last.pop(username, None)

    # --- Manual editing (admin GUI pencil/eraser) ---

    def _mean_speed(self) -> float:
        """Average learned driving speed, used for hand-drawn edges."""
        total_count, total_speed = 0, 0.0
        for nbs in self.adj.values():
            for count, speed_sum, _ts in nbs.values():
                total_count += count
                total_speed += speed_sum
        return total_speed / total_count if total_count else 12.0

    def draw(self, points: list[tuple[float, float]]) -> int:
        """Add a hand-drawn street along the 2D polyline (both directions).

        Map clicks carry no height, so each cell snaps onto the vertical
        layer of an existing node at that 2D cell where present (joining the
        learned network); elsewhere it continues on the previous layer,
        starting from the graph's most common layer. Returns the number of
        new directed edges.
        """
        if len(points) < 2:
            return 0

        # 2D cell -> existing vertical layers, for layer snapping.
        by2d: dict[tuple[int, int], list[int]] = {}
        layer_freq: dict[int, int] = {}
        for (cx, cz, cy) in self.adj:
            by2d.setdefault((cx, cz), []).append(cy)
            layer_freq[cy] = layer_freq.get(cy, 0) + 1
        layer = max(layer_freq, key=layer_freq.get) if layer_freq else 6  # y 60-69

        # Rasterise the polyline into deduplicated 2D cells.
        cells2d: list[tuple[int, int]] = []
        for (x1, z1), (x2, z2) in zip(points, points[1:]):
            dist = math.hypot(x2 - x1, z2 - z1)
            steps = max(1, math.ceil(dist / (GRID * 0.45)))
            for i in range(steps + 1):
                f = i / steps
                c = (math.floor((x1 + (x2 - x1) * f) / GRID),
                     math.floor((z1 + (z2 - z1) * f) / GRID))
                if not cells2d or cells2d[-1] != c:
                    cells2d.append(c)
        if len(cells2d) < 2:
            return 0

        speed = self._mean_speed()
        now_ms = int(time.time() * 1000)
        added = 0
        prev = None
        for c2 in cells2d:
            layers = by2d.get(c2)
            if layers:
                layer = min(layers, key=lambda ly: abs(ly - layer))
            node = (c2[0], c2[1], layer)
            if prev is not None and prev != node:
                for a, b in ((prev, node), (node, prev)):
                    stat = self.adj.setdefault(a, {}).get(b)
                    if stat is None:
                        self.adj[a][b] = [EDIT_COUNT, speed * EDIT_COUNT, now_ms]
                        self.edge_count += 1
                        added += 1
                    else:
                        stat[0] = max(stat[0], EDIT_COUNT)
                        stat[2] = now_ms
            prev = node
        if added:
            self._dirty = True
        return added

    def erase(self, points: list[tuple[float, float]], radius: float) -> int:
        """Remove every edge with an endpoint within ``radius`` blocks (2D)
        of the brush polyline — across all vertical layers, since the map
        eraser cannot see height. Returns the number of directed edges removed."""
        if not points:
            return 0
        pad = radius + GRID
        min_x = min(p[0] for p in points) - pad
        max_x = max(p[0] for p in points) + pad
        min_z = min(p[1] for p in points) - pad
        max_z = max(p[1] for p in points) + pad

        def near(px: float, pz: float) -> bool:
            if not (min_x <= px <= max_x and min_z <= pz <= max_z):
                return False
            if len(points) == 1:
                return math.hypot(px - points[0][0], pz - points[0][1]) <= radius
            for (x1, z1), (x2, z2) in zip(points, points[1:]):
                dx, dz = x2 - x1, z2 - z1
                len_sq = dx * dx + dz * dz
                if len_sq == 0:
                    d = math.hypot(px - x1, pz - z1)
                else:
                    t = max(0.0, min(1.0, ((px - x1) * dx + (pz - z1) * dz) / len_sq))
                    d = math.hypot(px - (x1 + t * dx), pz - (z1 + t * dz))
                if d <= radius:
                    return True
            return False

        hit_cache: dict[tuple[int, int, int], bool] = {}

        def node_hit(node) -> bool:
            hit = hit_cache.get(node)
            if hit is None:
                hit = near(*_center(node))
                hit_cache[node] = hit
            return hit

        removed = 0
        for a in list(self.adj):
            nbs = self.adj[a]
            a_hit = node_hit(a)
            for b in [b for b in nbs if a_hit or node_hit(b)]:
                del nbs[b]
                removed += 1
            if not nbs:
                del self.adj[a]
        self.edge_count -= removed
        if removed:
            self._dirty = True
        return removed

    # --- Routing ---

    def _nearest_node(self, x: float, z: float) -> tuple[int, int, int] | None:
        """Nearest node in 2D — query points come from map clicks and carry
        no height, so an overlap picks one layer arbitrarily. Both layers are
        real drivable roads, so the resulting route is still valid."""
        best, best_d = None, SNAP_RADIUS
        for node in self.adj:
            nx, nz = _center(node)
            d = math.hypot(nx - x, nz - z)
            if d < best_d:
                best, best_d = node, d
        return best

    def route(self, from_xz, to_xz, min_count: int = 1) -> dict | None:
        start = self._nearest_node(*from_xz)
        goal = self._nearest_node(*to_xz)
        if start is None or goal is None:
            return None

        # Dijkstra over travel time.
        dist_to: dict[tuple[int, int, int], float] = {start: 0.0}
        prev: dict[tuple[int, int, int], tuple[int, int, int]] = {}
        pq = [(0.0, start)]
        while pq:
            d, node = heapq.heappop(pq)
            if node == goal:
                break
            if d > dist_to.get(node, math.inf):
                continue
            for nb, (count, speed_sum, _ts) in self.adj.get(node, {}).items():
                if count < min_count:
                    continue
                seg_len = math.dist(_center(node), _center(nb))
                speed = max(speed_sum / count, 1.0)
                nd = d + seg_len / speed
                if nd < dist_to.get(nb, math.inf):
                    dist_to[nb] = nd
                    prev[nb] = node
                    heapq.heappush(pq, (nd, nb))

        if goal not in dist_to:
            return None

        nodes = [goal]
        while nodes[-1] != start:
            nodes.append(prev[nodes[-1]])
        nodes.reverse()

        path = [[round(c[0], 1), round(c[1], 1)] for c in map(_center, nodes)]
        length = sum(math.dist(_center(a), _center(b)) for a, b in zip(nodes, nodes[1:]))
        return {
            "path": path,
            "distanceBlocks": round(length, 1),
            "timeSeconds": round(dist_to[goal], 1),
        }

    # --- Views ---

    def stats(self) -> dict:
        return {
            "nodes": len(self.adj),
            "edges": self.edge_count,
            "segments": self.segments,
            "activeTracks": len(self._last),
        }

    def edges_for_display(self, min_count: int = 1, limit: int = 40_000) -> list:
        """Undirected merge for drawing: [x1, z1, x2, z2, count, avgSpeed]."""
        merged: dict[frozenset, list] = {}
        for a, nbs in self.adj.items():
            for b, (count, speed_sum, _ts) in nbs.items():
                if count < min_count:
                    continue
                key = frozenset((a, b))
                cur = merged.get(key)
                if cur is None:
                    merged[key] = [a, b, count, speed_sum]
                else:
                    cur[2] += count
                    cur[3] += speed_sum
        rows = sorted(merged.values(), key=lambda r: -r[2])[:limit]
        out = []
        for a, b, count, speed_sum in rows:
            (x1, z1), (x2, z2) = _center(a), _center(b)
            out.append([x1, z1, x2, z2, count, round(speed_sum / count, 1)])
        return out

    # --- Persistence ---

    def _prune(self) -> None:
        if self.edge_count <= MAX_EDGES:
            return
        cutoff = int(time.time() * 1000) - PRUNE_AGE_MS
        removed = 0
        for a in list(self.adj):
            nbs = self.adj[a]
            for b in [b for b, s in nbs.items() if s[0] <= 1 and s[2] < cutoff]:
                del nbs[b]
                removed += 1
            if not nbs:
                del self.adj[a]
        self.edge_count -= removed
        if removed:
            log.info("Nav graph pruned: %d stale single-use edges removed", removed)

    def save(self) -> None:
        if not self._dirty:
            return
        self._prune()
        data = {
            "grid": GRID,
            "yLayer": Y_LAYER,
            "segments": self.segments,
            "edges": {
                f"{a[0]},{a[1]},{a[2]}|{b[0]},{b[1]},{b[2]}": s
                for a, nbs in self.adj.items() for b, s in nbs.items()
            },
        }
        config.DATA_DIR.mkdir(parents=True, exist_ok=True)
        tmp = GRAPH_PATH.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, separators=(",", ":")), encoding="utf-8")
        os.replace(tmp, GRAPH_PATH)
        self._dirty = False
        log.info("Nav graph saved: %d nodes, %d edges", len(self.adj), self.edge_count)

    def load(self) -> None:
        if not GRAPH_PATH.exists():
            return
        try:
            data = json.loads(GRAPH_PATH.read_text(encoding="utf-8"))
            if data.get("grid") != GRID or data.get("yLayer") != Y_LAYER:
                log.warning("Nav graph grid/layer size changed — starting fresh")
                return
            self.segments = int(data.get("segments", 0))
            for key, stat in data.get("edges", {}).items():
                a_str, b_str = key.split("|")
                a = tuple(int(v) for v in a_str.split(","))
                b = tuple(int(v) for v in b_str.split(","))
                self.adj.setdefault(a, {})[b] = [int(stat[0]), float(stat[1]), int(stat[2])]
                self.edge_count += 1
            log.info("Nav graph loaded: %d nodes, %d edges", len(self.adj), self.edge_count)
        except Exception as e:
            log.warning("Nav graph load failed (starting fresh): %s", e)

    async def autosave_loop(self) -> None:
        while True:
            await asyncio.sleep(AUTOSAVE_SECONDS)
            try:
                await asyncio.to_thread(self.save)
            except Exception as e:
                log.warning("Nav graph autosave failed: %s", e)


nav = NavGraph()
