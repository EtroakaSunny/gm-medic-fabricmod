package de.dorikku.gmmedicmod.network;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.config.BuildFlags;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.vehicle.VehicleAutomation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;

public class ApiConnection implements EmergencyCallManager.CallEventListener {

    private static final ApiConnection INSTANCE = new ApiConnection();

    public static ApiConnection getInstance() {
        return INSTANCE;
    }

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gm-medic-api");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket webSocket;
    private volatile boolean authenticated = false;
    private volatile boolean wantConnected = false;
    private volatile long lastPongMs = 0L;
    private int reconnectAttempts = 0;

    private ScheduledFuture<?> pingTask;
    private ScheduledFuture<?> locationTask;

    private ApiConnection() {}

    // --- Lifecycle ---

    public void connect() {
        if (!ApiConfig.getInstance().isConfigured()) {
            GMMedic.LOGGER.info("[ApiConnection] No serverUrl configured — connection skipped");
            return;
        }
        wantConnected = true;
        reconnectAttempts = 0;
        scheduleConnect(0);
    }

    public void disconnect() {
        wantConnected = false;
        cancelPeriodicTasks();
        authenticated = false;
        WebSocket ws = webSocket;
        webSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client disconnect").join();
            } catch (Exception ignored) {}
        }
        GMMedic.LOGGER.info("[ApiConnection] Disconnected");
    }

    private void scheduleConnect(long delaySeconds) {
        scheduler.schedule(this::doConnect, delaySeconds, TimeUnit.SECONDS);
    }

    private void doConnect() {
        if (!wantConnected || webSocket != null) return;
        String url = ApiConfig.getInstance().getServerUrl();
        try {
            URI uri = URI.create(url);
            WebSocket ws = httpClient.newWebSocketBuilder()
                    .buildAsync(uri, new WsListener())
                    .get(10, TimeUnit.SECONDS);
            webSocket = ws;
            reconnectAttempts = 0;
            Minecraft client = Minecraft.getInstance();
            String username = client.getUser().getName();
            String token = ApiConfig.getInstance().getAuthToken();
            sendRaw(OutboundMessages.auth(token, username));
            GMMedic.LOGGER.info("[ApiConnection] Connected to {}", url);
        } catch (Exception e) {
            GMMedic.LOGGER.warn("[ApiConnection] Connection failed: {}", e.getMessage());
            onWebSocketError();
        }
    }

    private void onWebSocketError() {
        webSocket = null;
        authenticated = false;
        cancelPeriodicTasks();
        if (wantConnected) {
            reconnectAttempts++;
            long delay = Math.min((long) reconnectAttempts * 5, 60);
            GMMedic.LOGGER.info("[ApiConnection] Reconnecting in {}s (attempt {})", delay, reconnectAttempts);
            scheduleConnect(delay);
        }
    }

    // --- Sending ---

    public void send(String json) {
        if (!authenticated) return;
        sendRaw(json);
    }

    private void sendRaw(String json) {
        WebSocket ws = webSocket;
        if (ws == null) return;
        try {
            ws.sendText(json, true);
        } catch (Exception e) {
            GMMedic.LOGGER.warn("[ApiConnection] Send failed: {}", e.getMessage());
        }
    }

    // --- Periodic tasks ---

    void startPeriodicTasks() {
        cancelPeriodicTasks();
        lastPongMs = System.currentTimeMillis();
        // The connection now outlives duty: only announce DUTY_ON when actually on duty
        // (e.g. re-auth after a reconnect); otherwise the client is just online.
        if (EmergencyCallManager.getInstance().isInDuty()) {
            String username = Minecraft.getInstance().getUser().getName();
            sendRaw(OutboundMessages.dutyOn(username));
            startLocationTask();
        }

        pingTask = scheduler.scheduleAtFixedRate(() -> {
            send(OutboundMessages.ping());
            if (lastPongMs > 0 && System.currentTimeMillis() - lastPongMs > 90_000L) {
                GMMedic.LOGGER.warn("[ApiConnection] No PONG for 90s — reconnecting");
                webSocket = null;
                authenticated = false;
                onWebSocketError();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Position tracking is duty-only: the task exists solely while on duty
     * (started on duty-on / after re-auth, cancelled on duty-off), so an
     * off-duty client sends no location data at all.
     */
    private void startLocationTask() {
        if (locationTask != null) return;
        locationTask = scheduler.scheduleAtFixedRate(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) return;
            String locationUsername = client.getUser().getName();
            double px = client.player.getX();
            double py = client.player.getY();
            double pz = client.player.getZ();
            boolean driving = VehicleAutomation.isDrivingCar(client);
            send(OutboundMessages.locationUpdate(locationUsername, px, py, pz, driving));
        }, 2, 2, TimeUnit.SECONDS);
    }

    private void stopLocationTask() {
        if (locationTask != null) { locationTask.cancel(false); locationTask = null; }
    }

    private void cancelPeriodicTasks() {
        if (pingTask != null) { pingTask.cancel(false); pingTask = null; }
        stopLocationTask();
    }

    // --- Package-visible for InboundDispatcher ---

    void setAuthenticated(boolean auth) {
        this.authenticated = auth;
    }

    void updateLastPong() {
        this.lastPongMs = System.currentTimeMillis();
    }

    public boolean isAuthenticated() { return authenticated; }
    public boolean isConnected() { return webSocket != null; }

    /**
     * Gate for every mod feature (vehicle automation, siren, HUD, call tracking): in the
     * gated build, nothing runs until the API server has actually verified this player
     * (AUTH_OK received). The unrestricted build ({@code -Prequire_verification=false})
     * skips this check entirely.
     */
    public boolean isFeatureUnlocked() {
        return !BuildFlags.REQUIRE_SERVER_VERIFICATION || authenticated;
    }

    // --- CallEventListener ---

    @Override
    public void onDutyChanged(boolean inDuty) {
        String username = Minecraft.getInstance().getUser().getName();
        if (inDuty) {
            if (authenticated) {
                send(OutboundMessages.dutyOn(username));
                startLocationTask();
            } else {
                // Not connected yet (e.g. dropped connection) — connecting will announce
                // the duty state after AUTH_OK via startPeriodicTasks().
                connect();
            }
        } else {
            // Stay connected: the client remains online for the whole game session,
            // but position tracking ends with the duty.
            send(OutboundMessages.dutyOff(username));
            stopLocationTask();
        }
    }

    @Override
    public void onCallNew(EmergencyCall call) {
        send(OutboundMessages.callNew(call));
    }

    @Override
    public void onCallAssigned(EmergencyCall call) {
        send(OutboundMessages.callAssigned(call));
    }

    @Override
    public void onCallResolved(EmergencyCall call) {
        send(OutboundMessages.callResolved(call));
    }

    @Override
    public void onCallRejected(EmergencyCall call) {
        send(OutboundMessages.callRejected(call));
    }

    // --- WebSocket listener ---

    private class WsListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                InboundDispatcher.dispatch(message);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            GMMedic.LOGGER.info("[ApiConnection] WebSocket closed: {} {}", statusCode, reason);
            onWebSocketError();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            GMMedic.LOGGER.warn("[ApiConnection] WebSocket error: {}", error.getMessage());
            onWebSocketError();
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
            ws.sendPong(message);
            ws.request(1);
            return null;
        }
    }
}
