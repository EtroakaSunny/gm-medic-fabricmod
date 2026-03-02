package de.dorikku.gmmedicmod.api;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.config.ApiConfig;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/**
 * HTTP API client for synchronizing emergency calls with a remote FastAPI server.
 * <p>
 * Lifecycle:
 * <ul>
 *   <li>{@link #connect(String)} — Called when the player enters duty. Starts polling.</li>
 *   <li>{@link #disconnect()} — Called when the player leaves duty. Stops all activity.</li>
 * </ul>
 * <p>
 * Outbound events are sent immediately (fire-and-forget async) when local state changes.
 * Inbound state is polled every N seconds and merged into {@link EmergencyCallManager}.
 * <p>
 * All requests carry an {@code X-API-Key} header for authentication and an
 * {@code X-Client-Id} header to identify this client instance.
 */
public class ApiClient {

    private static final ApiClient INSTANCE = new ApiClient();

    public static ApiClient getInstance() {
        return INSTANCE;
    }

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile String lastError = null;
    private volatile String playerName = null;

    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;

    // Track the last known call count from the API to avoid unnecessary processing
    private volatile long lastSyncTimestamp = 0;

    private ApiClient() {
    }

    // --- Lifecycle ---

    /**
     * Connects to the API and starts the polling loop.
     * Called when the player enters duty mode.
     *
     * @param playerName the local player's name (used in duty events)
     */
    public void connect(String playerName) {
        ApiConfig config = ApiConfig.getInstance();
        if (!config.isReady()) {
            GMMedic.LOGGER.warn("[ApiClient] Cannot connect: API not configured (enabled={}, url={}, key={})",
                    config.isEnabled(), config.getApiUrl(), config.getApiKey() != null && !config.getApiKey().isEmpty());
            return;
        }

        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
            GMMedic.LOGGER.debug("[ApiClient] Already connected or connecting, skipping");
            return;
        }

        this.playerName = playerName;
        state = ConnectionState.CONNECTING;
        lastError = null;
        lastSyncTimestamp = 0;

        GMMedic.LOGGER.info("[ApiClient] Connecting to API at {}", config.getApiUrl());

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GM-Medic-ApiClient");
            t.setDaemon(true);
            return t;
        });

        // Send duty_on event, then start polling
        scheduler.execute(() -> {
            try {
                sendDutyEvent(true);
                state = ConnectionState.CONNECTED;
                GMMedic.LOGGER.info("[ApiClient] Connected successfully");
            } catch (Exception e) {
                state = ConnectionState.ERROR;
                lastError = e.getMessage();
                GMMedic.LOGGER.error("[ApiClient] Connection failed", e);
            }
        });

        // Start periodic polling
        int interval = config.getPollIntervalSeconds();
        scheduler.scheduleAtFixedRate(this::pollCalls, interval, interval, TimeUnit.SECONDS);
    }

    /**
     * Disconnects from the API and stops all background activity.
     * Called when the player leaves duty or disconnects from the server.
     */
    public void disconnect() {
        if (state == ConnectionState.DISCONNECTED) return;

        GMMedic.LOGGER.info("[ApiClient] Disconnecting from API");

        // Try to send duty_off before shutting down
        if (state == ConnectionState.CONNECTED && httpClient != null) {
            try {
                sendDutyEvent(false);
            } catch (Exception e) {
                GMMedic.LOGGER.debug("[ApiClient] Failed to send duty_off on disconnect", e);
            }
        }

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        httpClient = null;
        state = ConnectionState.DISCONNECTED;
        lastError = null;
        playerName = null;
        lastSyncTimestamp = 0;
    }

    // --- Outbound: push local events to the API ---

    /**
     * Sends a new/finalized call to the API.
     */
    public void sendNewCall(EmergencyCall call) {
        if (state != ConnectionState.CONNECTED) return;
        executeAsync(() -> {
            String json = CallSerializer.toJson(call);
            postJson("/calls", json);
            GMMedic.LOGGER.debug("[ApiClient] Sent new call: {}", call.getCallerName());
        });
    }

    /**
     * Notifies the API that a call was accepted by a medic.
     */
    public void sendCallAccepted(String callerName, String medicName) {
        if (state != ConnectionState.CONNECTED) return;
        executeAsync(() -> {
            String json = CallSerializer.eventToJson("accepted", callerName, medicName);
            postJson("/calls/event", json);
            GMMedic.LOGGER.debug("[ApiClient] Sent accept: {} by {}", callerName, medicName);
        });
    }

    /**
     * Notifies the API that a call was rejected.
     */
    public void sendCallRejected(String callerName, String medicName) {
        if (state != ConnectionState.CONNECTED) return;
        executeAsync(() -> {
            String json = CallSerializer.eventToJson("rejected", callerName, medicName);
            postJson("/calls/event", json);
            GMMedic.LOGGER.debug("[ApiClient] Sent reject: {} by {}", callerName, medicName);
        });
    }

    /**
     * Notifies the API that a call was removed (withdrawn, revived, logged out, reached).
     */
    public void sendCallRemoved(String callerName, String reason) {
        if (state != ConnectionState.CONNECTED) return;
        executeAsync(() -> {
            String json = CallSerializer.eventToJson("removed", callerName, reason);
            postJson("/calls/event", json);
            GMMedic.LOGGER.debug("[ApiClient] Sent remove: {} ({})", callerName, reason);
        });
    }

    // --- Inbound: poll for remote state ---

    private void pollCalls() {
        if (state != ConnectionState.CONNECTED) return;

        try {
            ApiConfig config = ApiConfig.getInstance();
            String url = config.getApiUrl() + "/calls?since=" + lastSyncTimestamp
                    + "&clientId=" + config.getClientId();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-API-Key", config.getApiKey())
                    .header("X-Client-Id", config.getClientId())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                if (body != null && !body.isBlank() && !body.equals("[]")) {
                    List<EmergencyCall> remoteCalls = CallSerializer.fromJsonArray(body);
                    mergeRemoteCalls(remoteCalls);
                }
                lastSyncTimestamp = System.currentTimeMillis();
            } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                GMMedic.LOGGER.error("[ApiClient] Authentication failed (HTTP {}). Check your API key.", response.statusCode());
                state = ConnectionState.ERROR;
                lastError = "Authentication failed (HTTP " + response.statusCode() + ")";
            } else {
                GMMedic.LOGGER.warn("[ApiClient] Poll returned HTTP {}", response.statusCode());
            }
        } catch (Exception e) {
            GMMedic.LOGGER.debug("[ApiClient] Poll failed: {}", e.getMessage());
            // Don't set ERROR state for transient poll failures
        }
    }

    /**
     * Merges remote calls into the local EmergencyCallManager.
     * Dispatches to the Minecraft client thread for thread safety.
     */
    private void mergeRemoteCalls(List<EmergencyCall> remoteCalls) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        client.execute(() -> {
            EmergencyCallManager mgr = EmergencyCallManager.getInstance();
            if (!mgr.isInDuty()) return;

            for (EmergencyCall remote : remoteCalls) {
                mgr.mergeRemoteCall(remote);
            }
            GMMedic.LOGGER.debug("[ApiClient] Merged {} remote calls", remoteCalls.size());
        });
    }

    // --- Duty events ---

    private void sendDutyEvent(boolean onDuty) {
        String json = CallSerializer.dutyEventToJson(onDuty, playerName);
        postJson("/duty", json);
    }

    // --- HTTP helpers ---

    private void postJson(String path, String json) {
        try {
            ApiConfig config = ApiConfig.getInstance();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getApiUrl() + path))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", config.getApiKey())
                    .header("X-Client-Id", config.getClientId())
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                GMMedic.LOGGER.error("[ApiClient] Auth failed on POST {} (HTTP {})", path, response.statusCode());
                state = ConnectionState.ERROR;
                lastError = "Auth failed (HTTP " + response.statusCode() + ")";
            } else if (response.statusCode() >= 400) {
                GMMedic.LOGGER.warn("[ApiClient] POST {} returned HTTP {}: {}", path, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            GMMedic.LOGGER.warn("[ApiClient] POST {} failed: {}", path, e.getMessage());
        }
    }

    private void executeAsync(Runnable task) {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.execute(task);
        }
    }

    // --- Status ---

    public ConnectionState getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }

    public String getPlayerName() {
        return playerName;
    }
}


