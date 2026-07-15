package de.dorikku.gmmedicmod.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.model.EmergencyCall.CallType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class InboundDispatcher {

    private InboundDispatcher() {}

    public static void dispatch(String rawJson) {
        Minecraft client = Minecraft.getInstance();
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

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendOverlayMessage(
                Component.literal("[GM-Medic] Verbunden und authentifiziert.").withStyle(ChatFormatting.GREEN)
            );
        }
    }

    private static void handleAuthFail(JsonObject obj) {
        String reason = obj.has("reason") ? obj.get("reason").getAsString() : "UNKNOWN";
        GMMedic.LOGGER.warn("[ApiConnection] AUTH_FAIL: {}", reason);

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            String token = ApiConfig.getInstance().getAuthToken();
            client.player.sendSystemMessage(
                Component.literal("[GM-Medic] AUTH fehlgeschlagen: " + reason + ". Token: " + token)
                    .withStyle(ChatFormatting.RED)
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

        String dist = obj.has("distanceBlocks") && !obj.get("distanceBlocks").isJsonNull()
                ? String.valueOf(Math.round(obj.get("distanceBlocks").getAsDouble()))
                : null;

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            String text = "[GM-Medic] Nächster freier Sanitäter"
                    + (caller != null ? " für " + caller : "")
                    + ": " + nearestMedic
                    + (dist != null ? " (" + dist + "m)" : "");
            client.player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.AQUA));
        }
        GMMedic.LOGGER.info("[ApiConnection] Nearest free medic for {}: {}", callId, nearestMedic);
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
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        String caller = call.getCallerName();
        String text = "[GM-Medic] " + medicName + " ist bereits in der Nähe"
                + (caller != null ? " von " + caller : "")
                + " (" + Math.round(distanceBlocks) + "m) und dürfte gleich ankommen.";
        client.player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.AQUA));
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
