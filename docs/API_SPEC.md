# GM-Medic Sync API — FastAPI Implementation Specification

## Overview

This document specifies a **FastAPI** backend that synchronizes emergency call data between multiple Minecraft clients running the **GM-Medic Fabric mod**. Each client connects when its player enters "duty mode" and disconnects when they leave. The API acts as a central relay: clients push local events (new calls, accepts, rejects, removals) and poll for the aggregated state.

---

## Authentication

All endpoints require **API key authentication** via the `X-API-Key` HTTP header.

### Configuration
- The server stores a list of valid API keys (e.g. in an `.env` file or a `config.yaml`).
- Each key can optionally be associated with a label/owner for auditing.

### Validation
- Every request must include `X-API-Key: <key>`.
- If the key is missing or invalid, return `401 Unauthorized` with body: `{"detail": "Invalid or missing API key"}`.
- Implement this as a **FastAPI dependency** (e.g. `Depends(verify_api_key)`).

### Additional Header
- Clients also send `X-Client-Id: <uuid>` to identify themselves. This is used to avoid echoing a client's own events back to it during polling.

---

## Data Models

### EmergencyCall

```json
{
  "callerName": "string",          // Player who made the emergency call
  "reason": "string",              // Reason for the call (e.g. "Herzinfarkt", "Tötungsdelikt")
  "x": 0.0,                        // X coordinate
  "y": 0.0,                        // Y coordinate
  "z": 0.0,                        // Z coordinate
  "locationName": "string",        // Named location (e.g. "Offenbach Nord"), can be empty
  "type": "ECALL | DEATH",         // ECALL = normal emergency, DEATH = death/revival timer
  "creationTime": 1709406000000,   // Epoch milliseconds when the call was created on the client
  "deadlineMs": -1,                // For DEATH calls: absolute epoch ms when revival timer expires. -1 = no timer
  "pending": false,                // Should always be false for API-synced calls
  "assignedMedic": "string | null",// Medic who accepted the call, or null
  "rejected": false,               // Whether the call was rejected
  "rejectedBy": "string | null",   // Name of the medic who rejected it
  "rejectedAtMs": -1               // Epoch ms when rejected, -1 if not rejected
}
```

### CallEvent

```json
{
  "event": "accepted | rejected | removed",
  "callerName": "string",          // Which call this event applies to
  "data": "string | null"          // For "accepted"/"rejected": medic name. For "removed": reason string
}
```

### DutyEvent

```json
{
  "event": "duty_on | duty_off",
  "playerName": "string"           // The player entering/leaving duty
}
```

---

## Endpoints

### 1. `POST /duty` — Report duty status change

**Description:** Called when a client's player enters or leaves duty mode.

**Headers:**
- `X-API-Key: <key>` (required)
- `X-Client-Id: <uuid>` (required)
- `Content-Type: application/json`

**Request Body:** `DutyEvent`

**Behavior:**
- On `duty_on`: Register this client as active. Store client ID, player name, and timestamp.
- On `duty_off`: Mark this client as inactive. Optionally clean up any calls that were only known to this client.

**Response:** `200 OK`
```json
{
  "status": "ok",
  "activeDutyCount": 3     // Number of currently active duty clients
}
```

---

### 2. `POST /calls` — Submit a new emergency call

**Description:** Called when a client receives a new finalized emergency call from the Minecraft server's chat.

**Headers:**
- `X-API-Key: <key>` (required)
- `X-Client-Id: <uuid>` (required)
- `Content-Type: application/json`

**Request Body:** `EmergencyCall`

**Behavior:**
- If a call with the same `callerName` already exists in the server store, update it (idempotent).
- If it's new, add it to the store.
- Record the source `clientId` and timestamp.

**Response:** `201 Created`
```json
{
  "status": "created",
  "callerName": "Toxic_padz"
}
```

---

### 3. `POST /calls/event` — Submit a call state change

**Description:** Called when a call is accepted, rejected, or removed.

**Headers:**
- `X-API-Key: <key>` (required)
- `X-Client-Id: <uuid>` (required)
- `Content-Type: application/json`

**Request Body:** `CallEvent`

**Behavior:**
- `accepted`: Set `assignedMedic` on the matching call to the value in `data`.
- `rejected`: Set `rejected=true`, `rejectedBy=data`, `rejectedAtMs=now` on the matching call.
- `removed`: Remove the call with the matching `callerName` from the store entirely.
- If no matching call is found, return `200 OK` anyway (idempotent).
- Record the event with source `clientId` and timestamp for sync purposes.

**Response:** `200 OK`
```json
{
  "status": "ok",
  "event": "accepted",
  "callerName": "F3lixus"
}
```

---

### 4. `GET /calls` — Poll for current call state

**Description:** Called periodically (every 2–5 seconds) by each client to get the aggregated list of active calls.

**Headers:**
- `X-API-Key: <key>` (required)
- `X-Client-Id: <uuid>` (required)
- `Accept: application/json`

**Query Parameters:**
- `since` (optional, integer): Epoch milliseconds. If provided, only return calls that have been created or modified since this timestamp. If omitted, return all active calls.
- `clientId` (required, string): The requesting client's ID. The server may use this to filter or annotate responses.

**Behavior:**
- Return all active (non-removed) calls, optionally filtered by `since`.
- The server should automatically clean up:
  - Calls that were rejected more than 30 seconds ago.
  - DEATH calls whose `deadlineMs` has passed by more than 30 seconds.
- Do NOT include calls that the requesting client just submitted in the same poll cycle (to avoid double-display). Use a short grace period (e.g. 500ms) or track per-client submission timestamps.

**Response:** `200 OK`
```json
[
  {
    "callerName": "Toxic_padz",
    "reason": "Tötungsdelikt",
    "x": -1488.0,
    "y": 63.0,
    "z": -2095.0,
    "locationName": "Offenbach Nord",
    "type": "DEATH",
    "creationTime": 1709406000000,
    "deadlineMs": 1709406298000,
    "pending": false,
    "assignedMedic": "DrHouse",
    "rejected": false,
    "rejectedBy": null,
    "rejectedAtMs": -1
  }
]
```

---

### 5. `GET /health` — Health check

**Description:** Simple health check for monitoring.

**No authentication required.**

**Response:** `200 OK`
```json
{
  "status": "healthy",
  "activeDutyClients": 3,
  "activeCalls": 5,
  "uptime": 3600
}
```

---

### 6. `GET /duty` — List active duty clients

**Description:** Returns a list of currently active duty clients.

**Headers:**
- `X-API-Key: <key>` (required)

**Response:** `200 OK`
```json
{
  "clients": [
    {
      "clientId": "uuid-1",
      "playerName": "mmlp12345",
      "connectedSince": 1709405000000,
      "lastSeen": 1709406000000
    }
  ]
}
```

---

## Server-Side Storage

Use **in-memory storage** (e.g. Python dicts) for simplicity and speed. No database needed — the data is ephemeral and only relevant while clients are connected.

### Suggested Data Structures

```python
# Active duty clients: clientId -> ClientInfo
active_clients: dict[str, ClientInfo] = {}

# Active emergency calls: callerName -> StoredCall
active_calls: dict[str, StoredCall] = {}

# Event log for sync (ring buffer or time-limited list)
event_log: list[EventEntry] = []
```

### StoredCall (extends EmergencyCall)
```python
class StoredCall:
    call: EmergencyCall         # The call data
    source_client_id: str       # Which client submitted this call
    created_at: float           # Server timestamp
    last_modified: float        # Server timestamp of last state change
```

### Cleanup
Run a background task (every 10-15 seconds) that:
- Removes calls where `rejected` is true and `rejectedAtMs` is older than 30 seconds.
- Removes DEATH calls where `deadlineMs` has passed by more than 30 seconds.
- Removes inactive clients that haven't polled in more than 60 seconds.

---

## Security Considerations

1. **API Key Validation**: All mutating and data endpoints require a valid API key.
2. **Rate Limiting**: Implement rate limiting per client ID (e.g. 60 requests/minute for polls, 10/minute for mutations).
3. **Input Validation**: Use Pydantic models for all request bodies. Validate string lengths (callerName max 16 chars, reason max 200 chars, etc.).
4. **HTTPS**: In production, the API MUST be served behind HTTPS (use a reverse proxy like nginx/caddy with TLS).
5. **CORS**: Configure CORS to only allow necessary origins, or disable it entirely if clients are not browsers.
6. **No sensitive data in logs**: Don't log API keys.

---

## Environment Configuration

Use a `.env` file or environment variables:

```env
# Comma-separated list of valid API keys
API_KEYS=key1,key2,key3

# Server host and port
HOST=0.0.0.0
PORT=8000

# Rate limiting
RATE_LIMIT_POLL=60        # max polls per minute per client
RATE_LIMIT_MUTATE=30      # max mutations per minute per client

# Cleanup intervals (seconds)
CLEANUP_INTERVAL=15
CLIENT_TIMEOUT=60
REJECTED_CALL_TTL=30
EXPIRED_DEATH_TTL=30
```

---

## Example Client Flow

1. Player enters duty → Client sends `POST /duty` with `{"event": "duty_on", "playerName": "mmlp12345"}`
2. Client starts polling `GET /calls?clientId=<id>` every 3 seconds
3. New death call arrives in Minecraft chat → Client parses it and sends `POST /calls` with the full call data
4. Another client polls and receives the new call → merges into its local state
5. A medic accepts the call → Client sends `POST /calls/event` with `{"event": "accepted", "callerName": "Toxic_padz", "data": "DrHouse"}`
6. Other clients receive the updated call (with `assignedMedic` set) on next poll
7. Call is revived/resolved → Client sends `POST /calls/event` with `{"event": "removed", "callerName": "Toxic_padz", "data": "revived"}`
8. Player leaves duty → Client sends `POST /duty` with `{"event": "duty_off", "playerName": "mmlp12345"}`
9. Client stops polling

---

## Tech Stack Requirements

- **Python 3.11+**
- **FastAPI** (latest)
- **Uvicorn** as ASGI server
- **Pydantic v2** for data validation
- **python-dotenv** for env config
- No database — in-memory only
- Optional: **slowapi** for rate limiting

---

## File Structure Suggestion

```
gm-medic-api/
├── .env
├── .env.example
├── requirements.txt
├── main.py              # FastAPI app, startup, shutdown
├── config.py            # Load .env, settings
├── auth.py              # API key verification dependency
├── models.py            # Pydantic models (EmergencyCall, CallEvent, DutyEvent, etc.)
├── store.py             # In-memory storage and cleanup logic
├── routes/
│   ├── calls.py         # POST /calls, POST /calls/event, GET /calls
│   ├── duty.py          # POST /duty, GET /duty
│   └── health.py        # GET /health
└── README.md
```

