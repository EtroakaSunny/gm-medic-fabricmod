package de.dorikku.gmmedicmod.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.manager.AlarmManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.model.EmergencyCall.CallType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;

public final class InboundDispatcher {

    private static final long NEAREST_DEDUPE_MS = 10_000L;
    private static String lastNearestKey = null;
    private static long lastNearestMs = 0L;

    private InboundDispatcher() {}

    public static void dispatch(String rawJson) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> handle(rawJson));
    }

    private static void handle(String rawJson) {
        try {
            JsonObject obj = JsonParser.parseString(rawJson).getAsJsonObject();
            String type = obj.get("type").getAsString();
            switch (type) {
                case "AUTH_OK"        -> handleAuthOk(obj);
                case "AUTH_FAIL"      -> handleAuthFail(obj);
                case "NEAREST_MEDIC"  -> handleNearestMedic(obj);
                case "OPEN_CALLS"     -> handleOpenCalls(obj);
                case "CALL_SYNC"      -> handleCallSync(obj);
                case "CALL_REMOVED"   -> handleCallRemoved(obj);
                case "ALARM_SYNC"     -> handleAlarmSync(obj);
                case "PONG"           -> handlePong();
                case "ERROR"          -> handleError(obj);
                default               -> GMMedic.LOGGER.debug("[ApiConnection] Unknown inbound type: {}", type);
            }
        } catch (Exception e) {
            GMMedic.LOGGER.warn("[ApiConnection] Failed to parse inbound message: {}", rawJson, e);
        }
    }

    private static void handleAuthOk(JsonObject obj) {
        ApiConnection conn = ApiConnection.getInstance();
        conn.setAuthenticated(true);
        conn.startPeriodicTasks();
        GMMedic.LOGGER.info("[ApiConnection] Authenticated successfully");

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                Text.literal("[GM-Medic] Verbunden und authentifiziert.").formatted(Formatting.GREEN),
                true
            );
        }
    }

    private static void handleAuthFail(JsonObject obj) {
        String reason = obj.has("reason") ? obj.get("reason").getAsString() : "UNKNOWN";
        GMMedic.LOGGER.warn("[ApiConnection] AUTH_FAIL: {}", reason);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            String token = ApiConfig.getInstance().getAuthToken();
            client.player.sendMessage(
                Text.literal("[GM-Medic] AUTH fehlgeschlagen: " + reason + ". Token: " + token)
                    .formatted(Formatting.RED),
                false
            );
        }
    }

    private static void handleNearestMedic(JsonObject obj) {
        String callId = optString(obj, "callId");
        String nearestMedic = optString(obj, "nearestMedic");
        // If no medic is free (everyone occupied or dead), the server sends nothing —
        // but guard anyway so we never announce an empty suggestion.
        if (callId == null || nearestMedic == null || nearestMedic.isBlank()) return;

        EmergencyCall call = EmergencyCallManager.getInstance().findByCallId(callId);
        String caller = call != null ? call.getCallerName() : null;
        if (call != null) call.setSuggestedMedic(nearestMedic);

        // The broadcast reaches every connected client; only on-duty medics care.
        if (!EmergencyCallManager.getInstance().isInDuty()) return;

        // Several clients can report the same transmission (each with its own callId),
        // so the server may broadcast the suggestion more than once — announce each
        // caller/medic pair only once per window.
        String dedupeKey = (caller != null ? caller.toLowerCase(Locale.ROOT) : callId) + "|" + nearestMedic;
        long now = System.currentTimeMillis();
        if (dedupeKey.equals(lastNearestKey) && now - lastNearestMs < NEAREST_DEDUPE_MS) return;
        lastNearestKey = dedupeKey;
        lastNearestMs = now;

        String dist = obj.has("distanceBlocks") && !obj.get("distanceBlocks").isJsonNull()
                ? String.valueOf(Math.round(obj.get("distanceBlocks").getAsDouble()))
                : null;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            String suffix;
            if (caller != null && dist != null) suffix = " (für " + caller + ", " + dist + "m)";
            else if (caller != null)            suffix = " (für " + caller + ")";
            else if (dist != null)              suffix = " (" + dist + "m)";
            else                                suffix = "";
            String text = "GM-Medic: Der nächste Medic ist: " + nearestMedic + suffix;
            client.player.sendMessage(Text.literal(text).formatted(Formatting.AQUA), false);
        }
        GMMedic.LOGGER.info("[ApiConnection] Nearest medic for {}: {}", callId, nearestMedic);
    }

    private static void handleOpenCalls(JsonObject obj) {
        if (!obj.has("calls") || !obj.get("calls").isJsonArray()) return;
        JsonArray calls = obj.getAsJsonArray("calls");
        int count = 0;
        for (JsonElement el : calls) {
            // Bulk sync on duty start — never announce, these assignments may be old news.
            if (el.isJsonObject()) { applyRemoteCall(el.getAsJsonObject(), false); count++; }
        }
        GMMedic.LOGGER.info("[ApiConnection] Synced {} open call(s) on duty start", count);
    }

    private static void handleCallSync(JsonObject obj) {
        if (obj.has("call") && obj.get("call").isJsonObject()) {
            applyRemoteCall(obj.getAsJsonObject("call"), true);
        }
    }

    private static void handleCallRemoved(JsonObject obj) {
        String callId = optString(obj, "callId");
        if (callId != null) EmergencyCallManager.getInstance().removeByCallId(callId);
    }

    private static void handleAlarmSync(JsonObject obj) {
        boolean active = obj.has("active") && !obj.get("active").isJsonNull() && obj.get("active").getAsBoolean();
        if (active) {
            String name = optString(obj, "alarmName");
            long triggeredAt = obj.has("triggeredAtMs") && !obj.get("triggeredAtMs").isJsonNull()
                    ? obj.get("triggeredAtMs").getAsLong() : 0L;
            AlarmManager.getInstance().triggerFromRemote(name != null ? name : "Unbekannt", triggeredAt);
        } else {
            AlarmManager.getInstance().endFromRemote();
        }
    }

    private static void applyRemoteCall(JsonObject c, boolean announceTransitions) {
        String callId = optString(c, "callId");
        if (callId == null) return;
        EmergencyCallManager manager = EmergencyCallManager.getInstance();
        EmergencyCall existing = manager.findByCallId(callId);
        String previousAssignedMedic = existing != null ? existing.getAssignedMedic() : null;

        CallType type = "DEATH".equals(optString(c, "callType")) ? CallType.DEATH : CallType.ECALL;
        String assignedMedic = optString(c, "assignedMedic");
        EmergencyCall call = manager.upsertRemoteCall(
                callId,
                type,
                optString(c, "callerName"),
                optString(c, "reason"),
                optDouble(c, "x"),
                optDouble(c, "y"),
                optDouble(c, "z"),
                optString(c, "locationName"),
                c.has("deadlineMs") && !c.get("deadlineMs").isJsonNull() ? c.get("deadlineMs").getAsLong() : -1L,
                assignedMedic,
                optString(c, "suggestedMedic"),
                c.has("resolved") && !c.get("resolved").isJsonNull() && c.get("resolved").getAsBoolean(),
                optString(c, "resolveReason"),
                optString(c, "rejectedBy")
        );

        double nearbyDistance = optDouble(c, "assignedMedicNearbyDistance");
        if (announceTransitions && assignedMedic != null && !assignedMedic.equals(previousAssignedMedic)
                && !Double.isNaN(nearbyDistance)) {
            announceMedicNearby(call, assignedMedic, nearbyDistance);
        }
    }

    /**
     * The server only sends {@code assignedMedicNearbyDistance} when the newly assigned medic's
     * last known position was within its nearby threshold, so any value here is worth announcing.
     */
    private static void announceMedicNearby(EmergencyCall call, String medicName, double distanceBlocks) {
        // The broadcast reaches every connected client; only on-duty medics care.
        if (!EmergencyCallManager.getInstance().isInDuty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        String caller = call.getCallerName();
        String text = "[GM-Medic] " + medicName + " ist bereits in der Nähe"
                + (caller != null ? " von " + caller : "")
                + " (" + Math.round(distanceBlocks) + "m) und dürfte gleich ankommen.";
        client.player.sendMessage(Text.literal(text).formatted(Formatting.AQUA), false);
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static double optDouble(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : Double.NaN;
    }

    private static void handlePong() {
        ApiConnection.getInstance().updateLastPong();
    }

    private static void handleError(JsonObject obj) {
        String code = obj.has("code") ? obj.get("code").getAsString() : "UNKNOWN";
        String message = obj.has("message") ? obj.get("message").getAsString() : "";
        GMMedic.LOGGER.warn("[ApiConnection] Server error {}: {}", code, message);
    }
}
