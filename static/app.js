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

// --- View switching ---

const loginView = document.getElementById("login-view");
const dashView = document.getElementById("dash-view");

function showLogin() {
    loginView.classList.remove("hidden");
    dashView.classList.add("hidden");
}
function showDash() {
    loginView.classList.add("hidden");
    dashView.classList.remove("hidden");
    initMap();
    // The panel just became visible — Leaflet needs a size recalculation.
    requestAnimationFrame(() => map && map.invalidateSize());
    connectWs();
    refreshMedics();
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
        const del = e.inDb ? `<button class="del" title="Entfernen">×</button>` : "";
        li.innerHTML = `<div class="row"><span class="name">${escapeHtml(e.display)}</span>${status}</div>
                        <div class="row"><span class="meta">${escapeHtml(e.username)}</span>${del}</div>`;
        if (e.inDb) li.querySelector(".del").addEventListener("click", () => removeMedic(e.username));
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

// The map is optional: if it cannot start (e.g. Leaflet missing because a
// stale cached page is in play), the dashboard must still work — never throw.
function initMap() {
    if (map) return;
    if (typeof L === "undefined") {
        document.getElementById("map").textContent =
            "Karte konnte nicht geladen werden — bitte Seite neu laden (Strg+F5).";
        return;
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

        map = L.map("map", {
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
        }).addTo(map);
        map.setView([0, 0], 1);
        document.getElementById("map-fit").addEventListener("click", () => fitMapToMarkers(true));
    } catch (e) {
        console.error("Karte konnte nicht initialisiert werden:", e);
        map = null;
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

window.addEventListener("resize", () => { if (map) map.invalidateSize(); });

// --- Utils ---

function escapeHtml(s) {
    return String(s == null ? "" : s)
        .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

// --- Boot ---

if (getToken()) showDash(); else showLogin();
