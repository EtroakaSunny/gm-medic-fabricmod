"use strict";

const TOKEN_KEY = "gm_token";

// In-memory live state mirrored from the admin WebSocket.
const medics = new Map(); // username -> {username,x,y,z,on_duty,last_seen}
const calls = new Map();  // callId -> call

let ws = null;
let reconnectTimer = null;

// --- Auth helpers ---

function getToken() { return localStorage.getItem(TOKEN_KEY); }
function setToken(t) { localStorage.setItem(TOKEN_KEY, t); }
function clearToken() { localStorage.removeItem(TOKEN_KEY); }

async function api(path, opts = {}) {
    const headers = opts.headers || {};
    const token = getToken();
    if (token) headers["Authorization"] = "Bearer " + token;
    if (opts.body) headers["Content-Type"] = "application/json";
    const res = await fetch(path, { ...opts, headers });
    if (res.status === 401) { logout(); throw new Error("unauthorized"); }
    return res;
}

// --- Current account (role + view permissions) ---

let me = null; // {username, role, permissions: [...]}

function can(perm) {
    return me !== null && (me.role === "admin" || me.permissions.includes(perm));
}
function isAdmin() {
    return me !== null && me.role === "admin";
}

async function loadMe() {
    const res = await api("/api/me");
    me = await res.json();
}

// Show only the panels/tabs this account may see; hide management for non-admins.
function applyPermissions() {
    document.getElementById("medics-panel").classList.toggle("hidden", !can("medics"));
    document.getElementById("map-panel").classList.toggle("hidden", !can("map"));
    document.getElementById("calls-panel").classList.toggle("hidden", !can("calls"));
    document.getElementById("tab-beta").classList.toggle("hidden", !can("nav"));
    document.getElementById("add-medic-form").classList.toggle("hidden", !isAdmin());
    document.getElementById("user-admin-panel").classList.toggle("hidden", !isAdmin());
    // Re-pack the main grid so hidden panels don't leave empty columns.
    const cols = [];
    if (can("medics")) cols.push("280px");
    if (can("map")) cols.push("1fr");
    if (can("calls")) cols.push("320px");
    document.getElementById("main-view").style.gridTemplateColumns = cols.join(" ") || "1fr";
    document.getElementById("account-whoami").textContent =
        `Angemeldet als ${me.username} (${me.role === "admin" ? "Admin" : "Benutzer"})`;
}

// --- View switching ---

const loginView = document.getElementById("login-view");
const dashView = document.getElementById("dash-view");

function showLogin() {
    loginView.classList.remove("hidden");
    dashView.classList.add("hidden");
}
async function showDash() {
    try {
        await loadMe();
    } catch {
        return; // 401 already logged us out; other errors leave the login view up
    }
    loginView.classList.add("hidden");
    dashView.classList.remove("hidden");
    applyPermissions();
    showTab("main");
    if (can("map")) {
        initMap();
        // The panel just became visible — Leaflet needs a size recalculation.
        requestAnimationFrame(() => map && map.invalidateSize());
    }
    connectWs();
    if (can("medics")) refreshMedics();
}

// --- Login ---

document.getElementById("login-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const errEl = document.getElementById("login-error");
    errEl.textContent = "";
    const username = document.getElementById("login-user").value.trim();
    const password = document.getElementById("login-pass").value;
    try {
        const res = await fetch("/api/auth/login", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ username, password }),
        });
        if (!res.ok) { errEl.textContent = "Anmeldung fehlgeschlagen"; return; }
        const data = await res.json();
        setToken(data.access_token);
        showDash();
    } catch {
        errEl.textContent = "Verbindungsfehler";
    }
});

function logout() {
    clearToken();
    me = null;
    if (ws) { try { ws.close(); } catch {} ws = null; }
    showLogin();
}
document.getElementById("logout-btn").addEventListener("click", logout);

// --- Admin WebSocket (live updates) ---

const connStatus = document.getElementById("conn-status");

function connectWs() {
    if (ws) { try { ws.close(); } catch {} }
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/ws/admin?token=${encodeURIComponent(getToken())}`);

    ws.onopen = () => { connStatus.textContent = "● live"; connStatus.className = "conn-status ok"; };
    ws.onclose = () => {
        connStatus.textContent = "○ getrennt"; connStatus.className = "conn-status bad";
        if (getToken()) {
            clearTimeout(reconnectTimer);
            reconnectTimer = setTimeout(connectWs, 3000);
        }
    };
    ws.onerror = () => { try { ws.close(); } catch {} };
    ws.onmessage = (ev) => handleWsMessage(JSON.parse(ev.data));
}

function handleWsMessage(msg) {
    switch (msg.type) {
        case "snapshot":
            medics.clear(); calls.clear();
            (msg.medics || []).forEach(m => medics.set(m.username, m));
            (msg.calls || []).forEach(c => calls.set(c.callId, c));
            break;
        case "medic_update":
            medics.set(msg.medic.username, msg.medic);
            break;
        case "medic_offline":
            medics.delete(msg.username);
            break;
        case "call_update":
            calls.set(msg.call.callId, msg.call);
            break;
        case "call_removed":
            calls.delete(msg.callId);
            break;
    }
    renderMedics();
    renderCalls();
    updateMap();
}

// --- Medic management ---

async function refreshMedics() {
    try {
        const res = await api("/api/medics");
        const dbMedics = await res.json();
        // Merge allow-list entries that may not be online into the panel.
        window._dbMedics = dbMedics;
        renderMedics();
    } catch {}
}

document.getElementById("add-medic-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const user = document.getElementById("add-medic-user").value.trim();
    const display = document.getElementById("add-medic-display").value.trim();
    if (!user) return;
    await api("/api/medics", {
        method: "POST",
        body: JSON.stringify({ username: user, display_name: display || null }),
    });
    e.target.reset();
    refreshMedics();
});

async function removeMedic(username) {
    if (!confirm(`Sanitäter "${username}" entfernen?`)) return;
    await api(`/api/medics/${encodeURIComponent(username)}`, { method: "DELETE" });
    refreshMedics();
}

function renderMedics() {
    const ul = document.getElementById("medic-list");
    ul.innerHTML = "";
    const db = window._dbMedics || [];
    const seen = new Set();

    const entries = db.map(m => {
        const live = medics.get(m.username);
        return {
            username: m.username,
            display: m.display_name || m.username,
            online: !!live,
            on_duty: live ? live.on_duty : false,
            inDb: true,
        };
    });
    db.forEach(m => seen.add(m.username));
    // Online medics not (yet) in the allow-list snapshot.
    medics.forEach((m, u) => {
        if (!seen.has(u)) entries.push({ username: u, display: u, online: true, on_duty: m.on_duty, inDb: false });
    });

    entries.sort((a, b) => (b.online - a.online) || a.username.localeCompare(b.username));

    for (const e of entries) {
        const li = document.createElement("li");
        const status = !e.online ? `<span class="badge off">offline</span>`
            : e.on_duty ? `<span class="badge duty">im Dienst</span>`
            : `<span class="badge off">außer Dienst</span>`;
        const del = (e.inDb && isAdmin()) ? `<button class="del" title="Entfernen">×</button>` : "";
        li.innerHTML = `<div class="row"><span class="name">${escapeHtml(e.display)}</span>${status}</div>
                        <div class="row"><span class="meta">${escapeHtml(e.username)}</span>${del}</div>`;
        if (del) li.querySelector(".del").addEventListener("click", () => removeMedic(e.username));
        ul.appendChild(li);
    }
}

// --- Calls ---

function renderCalls() {
    const ul = document.getElementById("call-list");
    ul.innerHTML = "";
    const list = [...calls.values()].sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
    for (const c of list) {
        const li = document.createElement("li");
        const typeBadge = c.callType === "DEATH"
            ? `<span class="badge death">Tod</span>`
            : `<span class="badge ecall">E-Call</span>`;
        const statusBadge = c.resolved ? `<span class="badge resolved">erledigt</span>` : "";
        const medic = c.assignedMedic ? `<div class="meta">Sanitäter: ${escapeHtml(c.assignedMedic)}</div>` : "";
        const loc = (c.locationName && c.locationName.length)
            ? escapeHtml(c.locationName)
            : (c.x != null ? `X ${Math.round(c.x)}, Z ${Math.round(c.z)}` : "unbekannt");
        li.innerHTML = `<div class="row"><span class="name">${escapeHtml(c.callerName || "?")}</span>${typeBadge}${statusBadge}</div>
                        <div class="meta">${escapeHtml(c.reason || "")}</div>
                        <div class="meta">${loc}</div>${medic}`;
        ul.appendChild(li);
    }
}

// --- Leaflet map on GermanMiner BlueMap tiles (proxied via /map/) ---
//
// BlueMap low-res tiles: 500×500 px PNGs, lodFactor 5, 3 LODs. At LOD 1 one
// pixel is one block; each LOD zooms out by 5×. We map Leaflet zoom 0/1/2 to
// LOD 3/2/1 with a custom CRS whose scale steps by 5 instead of 2, so tile
// indices line up exactly with BlueMap's floor(block / (500 · 5^(lod−1))).
// Block (x,z) lives at latLng(-z, x): 1 px = 1 block at zoom 2, north up.

const MAP_ID = "world";
const MAP_BASE = `map/maps/${MAP_ID}`;
const BLUEMAP_PUBLIC = "http://map.germanminer.de:2086";

let map = null;
let mapFitted = false;
const medicMarkers = new Map(); // username -> L.CircleMarker
const callMarkers = new Map();  // callId -> L.Marker

// Maps are optional: if one cannot start (e.g. Leaflet missing because a
// stale cached page is in play), the dashboard must still work — never throw.
function createBlueMapMap(containerId) {
    if (typeof L === "undefined") {
        document.getElementById(containerId).textContent =
            "Karte konnte nicht geladen werden — bitte Seite neu laden (Strg+F5).";
        return null;
    }
    try {
        const crs = L.extend({}, L.CRS.Simple, {
            scale: (zoom) => Math.pow(5, zoom) / 25,
            zoom: (scale) => Math.log(25 * scale) / Math.log(5),
        });

        // BlueMap low-res PNGs stack a data map below the color map (500×1000
        // px). A full-width <img> inside an overflow-hidden tile shows only the
        // top (color) square — no <canvas>, which browsers with strict
        // fingerprinting protection (e.g. Brave Shields) may block or blank.
        const BlueMapTileLayer = L.GridLayer.extend({
            createTile(coords, done) {
                const tile = document.createElement("div");
                tile.style.overflow = "hidden";
                const img = document.createElement("img");
                img.alt = "";
                img.style.width = "100%";
                img.onload = () => done(null, tile);
                img.onerror = () => { img.remove(); done(null, tile); }; // unrendered tile — stays transparent
                img.src = `${MAP_BASE}/tiles/${3 - coords.z}/x${coords.x}/z${coords.y}.png`;
                tile.appendChild(img);
                return tile;
            },
        });

        const m = L.map(containerId, {
            crs,
            minZoom: 0,
            maxZoom: 3,          // zoom 3 upscales LOD-1 tiles 5× for close-ups
            zoomSnap: 1,
            attributionControl: false,
        });
        new BlueMapTileLayer({
            tileSize: 500,
            minNativeZoom: 0,
            maxNativeZoom: 2,
        }).addTo(m);
        m.setView([0, 0], 1);
        return m;
    } catch (e) {
        console.error("Karte konnte nicht initialisiert werden:", e);
        return null;
    }
}

function initMap() {
    if (map) return;
    map = createBlueMapMap("map");
    if (map) {
        document.getElementById("map-fit").addEventListener("click", () => fitMapToMarkers(true));
    }
}

function blockLatLng(x, z) { return [-z, x]; }

function blueMapLink(x, y, z) {
    return `${BLUEMAP_PUBLIC}/#${MAP_ID}:${Math.round(x)}:${Math.round(y || 64)}:${Math.round(z)}:200:0:0:0:1:flat`;
}

function markerPopup(title, x, y, z) {
    return `<b>${escapeHtml(title)}</b><br>X ${Math.round(x)} · Z ${Math.round(z)}<br>` +
           `<a href="${blueMapLink(x, y, z)}" target="_blank" rel="noopener">In BlueMap öffnen</a>`;
}

function syncMarkers(existing, wanted, makeMarker) {
    for (const key of [...existing.keys()]) {
        if (!wanted.has(key)) { existing.get(key).remove(); existing.delete(key); }
    }
    wanted.forEach((data, key) => {
        const old = existing.get(key);
        if (old) old.remove();
        existing.set(key, makeMarker(data).addTo(map));
    });
}

function updateMap() {
    if (!map) return;

    const wantedMedics = new Map();
    medics.forEach((m, u) => { if (m.x != null && m.z != null) wantedMedics.set(u, m); });
    syncMarkers(medicMarkers, wantedMedics, (m) =>
        L.circleMarker(blockLatLng(m.x, m.z), {
            radius: 6, weight: 2, color: "#10141a",
            fillColor: m.on_duty ? "#46c46b" : "#8b93a1", fillOpacity: 1,
        })
        .bindTooltip(m.username, { permanent: true, direction: "right", offset: [8, 0], className: "map-label" })
        .bindPopup(markerPopup(m.username, m.x, m.y, m.z))
    );

    const wantedCalls = new Map();
    calls.forEach((c, id) => { if (!c.resolved && c.x != null && c.z != null) wantedCalls.set(id, c); });
    syncMarkers(callMarkers, wantedCalls, (c) =>
        L.marker(blockLatLng(c.x, c.z), {
            icon: L.divIcon({ className: `call-marker ${c.callType === "DEATH" ? "death" : "ecall"}`, iconSize: [12, 12] }),
        })
        .bindTooltip(c.callerName || "?", { permanent: true, direction: "right", offset: [8, 0], className: "map-label" })
        .bindPopup(markerPopup(`${c.callType === "DEATH" ? "Tod" : "E-Call"}: ${c.callerName || "?"}`, c.x, c.y, c.z))
    );

    if (!mapFitted && (medicMarkers.size || callMarkers.size)) {
        mapFitted = true;
        fitMapToMarkers(false);
    }
}

function fitMapToMarkers(animate) {
    if (!map) return;
    const layers = [...medicMarkers.values(), ...callMarkers.values()];
    if (!layers.length) return;
    map.fitBounds(L.featureGroup(layers).getBounds().pad(0.3), { maxZoom: 2, animate });
}

window.addEventListener("resize", () => {
    if (map) map.invalidateSize();
    if (navMap) navMap.invalidateSize();
});

// --- Tabs ---

const TABS = {
    main: document.getElementById("main-view"),
    beta: document.getElementById("beta-view"),
    account: document.getElementById("account-view"),
};

function showTab(which) {
    for (const [name, view] of Object.entries(TABS)) {
        view.classList.toggle("hidden", name !== which);
        document.getElementById(`tab-${name}`).classList.toggle("active", name === which);
    }
    if (which === "beta") initNavView();
    if (which === "account") initAccountView();
    requestAnimationFrame(() => {
        if (which === "beta" && navMap) navMap.invalidateSize();
        if (which === "main" && map) map.invalidateSize();
    });
}
for (const name of Object.keys(TABS)) {
    document.getElementById(`tab-${name}`).addEventListener("click", () => showTab(name));
}

// --- Beta: navigation (street network learned from drive traces) ---

let navMap = null;
let navGraphLayer = null;
let navRouteLayer = null;
let navStart = null; // {x, z} — first click; second click routes

function initNavView() {
    if (navMap) { refreshNavStats(); return; }
    navMap = createBlueMapMap("nav-map");
    if (!navMap) return;
    navMap.on("click", onNavMapClick);
    document.getElementById("nav-show-graph").addEventListener("click", loadNavGraph);
    document.getElementById("nav-clear-route").addEventListener("click", clearNavRoute);
    if (isAdmin()) initNavEditor();
    refreshNavStats();
}

async function refreshNavStats() {
    try {
        const res = await api("/api/nav/stats");
        const s = await res.json();
        document.getElementById("nav-stats").innerHTML =
            `<div class="row"><span class="meta">Knoten</span><span>${s.nodes}</span></div>` +
            `<div class="row"><span class="meta">Straßen-Segmente</span><span>${s.edges}</span></div>` +
            `<div class="row"><span class="meta">Erfasste Fahrten-Segmente</span><span>${s.segments}</span></div>` +
            `<div class="row"><span class="meta">Aktive Aufzeichnungen</span><span>${s.activeTracks}</span></div>`;
    } catch {}
}

async function loadNavGraph() {
    if (!navMap) return;
    setNavInfo("Lade Straßennetz …");
    try {
        const res = await api("/api/nav/graph?min_count=1");
        const data = await res.json();
        if (navGraphLayer) navGraphLayer.remove();
        // One multi-polyline per speed bucket keeps this fast even for
        // tens of thousands of segments (3 SVG paths in total).
        const slow = [], mid = [], fast = [];
        for (const [x1, z1, x2, z2, _count, speed] of data.edges) {
            const seg = [blockLatLng(x1, z1), blockLatLng(x2, z2)];
            (speed < 9 ? slow : speed < 16 ? mid : fast).push(seg);
        }
        navGraphLayer = L.layerGroup([
            L.polyline(slow, { color: "#8b93a1", weight: 2, opacity: 0.75, interactive: false }),
            L.polyline(mid,  { color: "#e6943c", weight: 2, opacity: 0.85, interactive: false }),
            L.polyline(fast, { color: "#46c46b", weight: 2, opacity: 0.85, interactive: false }),
        ]).addTo(navMap);
        setNavInfo(`Straßennetz: ${data.edges.length} Segmente.`);
        refreshNavStats();
        if (data.edges.length && !navRouteLayer) {
            const b = L.latLngBounds(data.edges.map(e => blockLatLng(e[0], e[1])));
            navMap.fitBounds(b.pad(0.2), { maxZoom: 2 });
        }
    } catch {
        setNavInfo("Straßennetz konnte nicht geladen werden.");
    }
}

function onNavMapClick(e) {
    const x = e.latlng.lng, z = -e.latlng.lat;
    if (navMode === "draw") {
        drawPoints.push([x, z]);
        updateDrawPreview();
        return;
    }
    if (navMode === "erase") return; // handled by mousedown/up drag
    if (navStart === null) {
        clearNavRoute();
        navStart = { x, z };
        navRouteLayer = L.layerGroup([
            L.circleMarker(blockLatLng(x, z), { radius: 7, weight: 2, color: "#10141a", fillColor: "#46c46b", fillOpacity: 1 }),
        ]).addTo(navMap);
        setNavInfo(`Start gesetzt (X ${Math.round(x)}, Z ${Math.round(z)}) — jetzt das Ziel anklicken.`);
    } else {
        requestNavRoute(navStart, { x, z });
        navStart = null;
    }
}

// --- Nav editor: pencil (add streets) and eraser (remove false routes) ---

let navMode = "route"; // "route" | "draw" | "erase"
let drawPoints = [];   // [[x, z], ...] pending pencil vertices
let drawPreview = null;
let eraseStroke = null; // {points: [[x, z], ...], line: L.Polyline} while dragging

const NAV_HINTS = {
    route: "Route: erst den Start, dann das Ziel auf der Karte anklicken.",
    draw: "Zeichnen: Punkte anklicken, dann „Übernehmen“. Esc bricht ab.",
    erase: "Radieren: mit gedrückter Maustaste über falsche Routen ziehen.",
};

function initNavEditor() {
    document.getElementById("nav-edit-tools").classList.remove("hidden");
    for (const mode of ["route", "draw", "erase"]) {
        document.getElementById(`nav-mode-${mode}`).addEventListener("click", () => setNavMode(mode));
    }
    document.getElementById("nav-draw-apply").addEventListener("click", applyDraw);
    document.getElementById("nav-draw-cancel").addEventListener("click", () => cancelDraw(true));
    document.getElementById("nav-consolidate").addEventListener("click", consolidateNav);
    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape" && navMode !== "route") setNavMode("route");
    });

    navMap.on("mousedown", (e) => {
        if (navMode !== "erase") return;
        eraseStroke = {
            points: [[e.latlng.lng, -e.latlng.lat]],
            line: L.polyline([e.latlng], {
                color: "#e5534b", opacity: 0.45, interactive: false,
                weight: Math.max(6, brushBlocks() * 2 * pxPerBlock()),
            }).addTo(navMap),
        };
    });
    navMap.on("mousemove", (e) => {
        if (!eraseStroke) return;
        eraseStroke.points.push([e.latlng.lng, -e.latlng.lat]);
        eraseStroke.line.addLatLng(e.latlng);
    });
    window.addEventListener("mouseup", finishErase);
}

function brushBlocks() { return Number(document.getElementById("nav-brush").value); }
function pxPerBlock() { return Math.pow(5, navMap.getZoom()) / 25; }

function setNavMode(mode) {
    if (mode !== "draw") cancelDraw(false);
    if (eraseStroke) { eraseStroke.line.remove(); eraseStroke = null; }
    navMode = mode;
    for (const m of ["route", "draw", "erase"]) {
        document.getElementById(`nav-mode-${m}`).classList.toggle("active", m === mode);
    }
    document.getElementById("nav-draw-confirm").classList.toggle("hidden", mode !== "draw");
    document.getElementById("nav-hint").textContent = NAV_HINTS[mode];
    // Dragging the map would fight the eraser stroke.
    if (mode === "erase") navMap.dragging.disable(); else navMap.dragging.enable();
    navMap.getContainer().style.cursor = mode === "route" ? "" : "crosshair";
    setNavInfo("");
}

function updateDrawPreview() {
    if (drawPreview) drawPreview.remove();
    drawPreview = L.layerGroup([
        L.polyline(drawPoints.map(p => blockLatLng(p[0], p[1])),
            { color: "#f0d05a", weight: 3, dashArray: "6 6", interactive: false }),
        ...drawPoints.map(p => L.circleMarker(blockLatLng(p[0], p[1]),
            { radius: 4, weight: 1, color: "#10141a", fillColor: "#f0d05a", fillOpacity: 1, interactive: false })),
    ]).addTo(navMap);
    setNavInfo(`${drawPoints.length} Punkt(e) gesetzt.`);
}

function cancelDraw(showHint) {
    drawPoints = [];
    if (drawPreview) { drawPreview.remove(); drawPreview = null; }
    if (showHint) setNavInfo("");
}

async function applyDraw() {
    if (drawPoints.length < 2) { setNavInfo("Mindestens 2 Punkte setzen."); return; }
    try {
        const res = await api("/api/nav/edit/draw", {
            method: "POST",
            body: JSON.stringify({ points: drawPoints }),
        });
        const r = await res.json();
        if (!res.ok) { setNavInfo(r?.detail || "Zeichnen fehlgeschlagen."); return; }
        cancelDraw(false);
        await loadNavGraph();
        setNavInfo(`${r.added} Segmente hinzugefügt.`);
    } catch {
        setNavInfo("Zeichnen fehlgeschlagen.");
    }
}

async function finishErase() {
    if (!eraseStroke) return;
    const { points, line } = eraseStroke;
    eraseStroke = null;
    line.remove();
    try {
        const res = await api("/api/nav/edit/erase", {
            method: "POST",
            body: JSON.stringify({ points, radius: brushBlocks() }),
        });
        const r = await res.json();
        if (!res.ok) { setNavInfo(r?.detail || "Radieren fehlgeschlagen."); return; }
        await loadNavGraph();
        setNavInfo(`${r.removed} Segmente entfernt.`);
    } catch {
        setNavInfo("Radieren fehlgeschlagen.");
    }
}

async function consolidateNav() {
    setNavInfo("Konsolidiere Straßennetz …");
    try {
        const res = await api("/api/nav/consolidate", { method: "POST" });
        const r = await res.json();
        if (!res.ok) { setNavInfo(r?.detail || "Konsolidierung fehlgeschlagen."); return; }
        if (navGraphLayer) await loadNavGraph();
        else refreshNavStats();
        setNavInfo(`${r.collapsed} Knoten zusammengefasst.`);
    } catch {
        setNavInfo("Konsolidierung fehlgeschlagen.");
    }
}

async function requestNavRoute(from, to) {
    setNavInfo("Berechne Route …");
    navRouteLayer.addLayer(
        L.circleMarker(blockLatLng(to.x, to.z), { radius: 7, weight: 2, color: "#10141a", fillColor: "#e5534b", fillOpacity: 1 })
    );
    try {
        const q = `fromX=${from.x.toFixed(1)}&fromZ=${from.z.toFixed(1)}&toX=${to.x.toFixed(1)}&toZ=${to.z.toFixed(1)}`;
        const res = await api(`/api/nav/route?${q}`);
        if (!res.ok) {
            const detail = (await res.json().catch(() => null))?.detail;
            setNavInfo(detail || "Keine Route gefunden.");
            return;
        }
        const r = await res.json();
        navRouteLayer.addLayer(L.polyline(r.path.map(p => blockLatLng(p[0], p[1])),
            { color: "#55b1f0", weight: 4, opacity: 0.9 }));
        const mins = Math.floor(r.timeSeconds / 60), secs = Math.round(r.timeSeconds % 60);
        setNavInfo(`Route: ${Math.round(r.distanceBlocks)} Blöcke, ca. ${mins}:${String(secs).padStart(2, "0")} min Fahrzeit.`);
    } catch {
        setNavInfo("Routenberechnung fehlgeschlagen.");
    }
}

function clearNavRoute() {
    navStart = null;
    if (navRouteLayer) { navRouteLayer.remove(); navRouteLayer = null; }
    setNavInfo("");
}

function setNavInfo(text) {
    document.getElementById("nav-route-info").textContent = text;
}

// --- Account: password self-service + (admins) user management ---

const PERM_LABELS = { medics: "Sanitäter", map: "Karte", calls: "Einsätze", nav: "Navigation" };

function initAccountView() {
    if (isAdmin()) loadUsers();
}

document.getElementById("pw-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const msg = document.getElementById("pw-msg");
    const oldPw = document.getElementById("pw-old").value;
    const newPw = document.getElementById("pw-new").value;
    if (newPw !== document.getElementById("pw-new2").value) {
        msg.textContent = "Die neuen Passwörter stimmen nicht überein.";
        return;
    }
    try {
        const res = await api("/api/me/password", {
            method: "POST",
            body: JSON.stringify({ old_password: oldPw, new_password: newPw }),
        });
        if (!res.ok) {
            msg.textContent = (await res.json().catch(() => null))?.detail || "Änderung fehlgeschlagen.";
            return;
        }
        e.target.reset();
        msg.textContent = "Passwort geändert.";
    } catch {
        msg.textContent = "Änderung fehlgeschlagen.";
    }
});

function permCheckboxes(container, checked, onChange) {
    container.innerHTML = "";
    for (const [key, label] of Object.entries(PERM_LABELS)) {
        const lab = document.createElement("label");
        const cb = document.createElement("input");
        cb.type = "checkbox";
        cb.checked = checked.includes(key);
        cb.dataset.perm = key;
        if (onChange) cb.addEventListener("change", onChange);
        lab.appendChild(cb);
        lab.appendChild(document.createTextNode(label));
        container.appendChild(lab);
    }
}

function readPerms(container) {
    return [...container.querySelectorAll("input:checked")].map(cb => cb.dataset.perm);
}

async function loadUsers() {
    try {
        const res = await api("/api/users");
        renderUsers(await res.json());
    } catch {}
}

function renderUsers(users) {
    const ul = document.getElementById("user-list");
    ul.innerHTML = "";
    for (const u of users) {
        const li = document.createElement("li");
        const self = u.username === me.username;
        const admin = u.role === "admin";
        const roleBadge = admin ? `<span class="badge duty">Admin</span>`
                                : `<span class="badge off">Benutzer</span>`;
        li.innerHTML = `<div class="row"><span class="name">${escapeHtml(u.username)}${self ? ' <span class="meta">(du)</span>' : ""}</span>
                            <span>${roleBadge}${self ? "" : '<button class="del" title="Löschen">×</button>'}</span></div>
                        <div class="user-controls"></div>`;
        const controls = li.querySelector(".user-controls");

        if (!self) {
            const roleSel = document.createElement("select");
            roleSel.innerHTML = `<option value="user">Benutzer</option><option value="admin">Admin</option>`;
            roleSel.value = u.role;
            roleSel.addEventListener("change", () => patchUser(u.username, { role: roleSel.value }));
            controls.appendChild(roleSel);

            const resetBtn = document.createElement("button");
            resetBtn.className = "ghost";
            resetBtn.textContent = "Passwort zurücksetzen";
            resetBtn.addEventListener("click", async () => {
                const pw = prompt(`Neues Passwort für ${u.username} (min. 8 Zeichen):`);
                if (pw) await patchUser(u.username, { password: pw });
            });
            controls.appendChild(resetBtn);

            li.querySelector(".del").addEventListener("click", async () => {
                if (!confirm(`Benutzer "${u.username}" löschen?`)) return;
                await api(`/api/users/${encodeURIComponent(u.username)}`, { method: "DELETE" });
                loadUsers();
            });
        }

        // Admins hold every permission implicitly — checkboxes only for users.
        if (!admin) {
            const perms = document.createElement("div");
            perms.className = "perms";
            permCheckboxes(perms, u.permissions, () =>
                patchUser(u.username, { permissions: readPerms(perms) }));
            controls.appendChild(perms);
        }

        ul.appendChild(li);
    }
}

async function patchUser(username, body) {
    try {
        const res = await api(`/api/users/${encodeURIComponent(username)}`, {
            method: "PATCH",
            body: JSON.stringify(body),
        });
        if (!res.ok) alert((await res.json().catch(() => null))?.detail || "Änderung fehlgeschlagen.");
    } catch {}
    loadUsers();
}

permCheckboxes(document.getElementById("add-user-perms"), ["medics", "map", "calls"]);

document.getElementById("add-user-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const msg = document.getElementById("add-user-msg");
    try {
        const res = await api("/api/users", {
            method: "POST",
            body: JSON.stringify({
                username: document.getElementById("add-user-name").value.trim(),
                password: document.getElementById("add-user-pass").value,
                role: document.getElementById("add-user-role").value,
                permissions: readPerms(document.getElementById("add-user-perms")),
            }),
        });
        if (!res.ok) {
            msg.textContent = (await res.json().catch(() => null))?.detail || "Anlegen fehlgeschlagen.";
            return;
        }
        e.target.reset();
        permCheckboxes(document.getElementById("add-user-perms"), ["medics", "map", "calls"]);
        msg.textContent = "Benutzer angelegt.";
        loadUsers();
    } catch {
        msg.textContent = "Anlegen fehlgeschlagen.";
    }
});

// --- Utils ---

function escapeHtml(s) {
    return String(s == null ? "" : s)
        .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

// --- Boot ---

if (getToken()) showDash(); else showLogin();
