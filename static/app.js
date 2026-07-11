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
    drawMap();
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

// --- Canvas map ---

const canvas = document.getElementById("map");
const ctx = canvas.getContext("2d");

function fitCanvas() {
    const rect = canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    return { w: rect.width, h: rect.height };
}

function drawMap() {
    if (dashView.classList.contains("hidden")) return;
    const { w, h } = fitCanvas();
    ctx.clearRect(0, 0, w, h);

    const points = [];
    medics.forEach(m => { if (m.x != null && m.z != null) points.push({ x: m.x, z: m.z }); });
    calls.forEach(c => { if (!c.resolved && c.x != null && c.z != null) points.push({ x: c.x, z: c.z }); });

    if (points.length === 0) {
        ctx.fillStyle = "#5a6270";
        ctx.font = "13px system-ui";
        ctx.textAlign = "center";
        ctx.fillText("Keine Positionsdaten", w / 2, h / 2);
        return;
    }

    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
    for (const p of points) {
        minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
        minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
    }
    // Avoid zero-size span.
    const spanX = Math.max(maxX - minX, 50);
    const spanZ = Math.max(maxZ - minZ, 50);
    const pad = 36;
    const scale = Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanZ);
    const cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;

    // World (x,z) -> screen. North (−Z) is up.
    const sx = (x) => w / 2 + (x - cx) * scale;
    const sy = (z) => h / 2 + (z - cz) * scale;

    // Grid
    ctx.strokeStyle = "#1b2129";
    ctx.lineWidth = 1;
    for (let gx = Math.ceil(minX / 100) * 100; gx <= maxX; gx += 100) {
        ctx.beginPath(); ctx.moveTo(sx(gx), 0); ctx.lineTo(sx(gx), h); ctx.stroke();
    }
    for (let gz = Math.ceil(minZ / 100) * 100; gz <= maxZ; gz += 100) {
        ctx.beginPath(); ctx.moveTo(0, sy(gz)); ctx.lineTo(w, sy(gz)); ctx.stroke();
    }

    // Calls (squares)
    ctx.font = "11px system-ui";
    ctx.textAlign = "left";
    calls.forEach(c => {
        if (c.resolved || c.x == null || c.z == null) return;
        const x = sx(c.x), y = sy(c.z);
        ctx.fillStyle = c.callType === "DEATH" ? "#e5534b" : "#e6943c";
        ctx.fillRect(x - 5, y - 5, 10, 10);
        ctx.fillStyle = "#cfd4dc";
        ctx.fillText(c.callerName || "?", x + 8, y + 4);
    });

    // Medics (circles)
    medics.forEach(m => {
        if (m.x == null || m.z == null) return;
        const x = sx(m.x), y = sy(m.z);
        ctx.beginPath();
        ctx.arc(x, y, 5, 0, Math.PI * 2);
        ctx.fillStyle = m.on_duty ? "#46c46b" : "#8b93a1";
        ctx.fill();
        ctx.fillStyle = "#cfd4dc";
        ctx.fillText(m.username, x + 8, y + 4);
    });
}

window.addEventListener("resize", drawMap);

// --- Utils ---

function escapeHtml(s) {
    return String(s == null ? "" : s)
        .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

// --- Boot ---

if (getToken()) showDash(); else showLogin();
