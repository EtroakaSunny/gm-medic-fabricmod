package de.dorikku.gmmedicmod.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.config.HudConfig;
import de.dorikku.gmmedicmod.manager.BloodDonationManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.model.EmergencyCall.CallType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;

public final class InboundDispatcher {

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
                case "BLOOD_STATUS"   -> handleBloodStatus(obj);
                case "BLOOD_SYNC"     -> handleBloodSync(obj);
                case "BLOOD_LIST"     -> handleBloodList(obj);
                case "UPDATE_AVAILABLE" -> handleUpdateAvailable(obj);
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

        String dist = obj.has("distanceBlocks") && !obj.get("distanceBlocks").isJsonNull()
                ? String.valueOf(Math.round(obj.get("distanceBlocks").getAsDouble()))
                : null;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            String text = "[GM-Medic] Nächster freier Sanitäter"
                    + (caller != null ? " für " + caller : "")
                    + ": " + nearestMedic
                    + (dist != null ? " (" + dist + "m)" : "");
            client.player.sendMessage(Text.literal(text).formatted(Formatting.AQUA), false);
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

    /**
     * A newer mod build exists. Purely informational — nothing is gated on it, the medic keeps
     * every feature. Handed to {@link UpdateNotifier}, which holds it back until a while after
     * the join and drops repeats from re-AUTHs.
     */
    private static void handleUpdateAvailable(JsonObject obj) {
        String latest = optString(obj, "latestVersion");
        if (latest == null) return;

        String current = optString(obj, "currentVersion");
        String downloadUrl = optString(obj, "downloadUrl");

        MutableText line = Text.literal("[GM-Medic] ").formatted(Formatting.GRAY)
                .append(Text.literal("Neue Version verfügbar: ").formatted(Formatting.YELLOW))
                .append(Text.literal(latest).formatted(Formatting.WHITE, Formatting.BOLD));
        if (current != null && !current.isBlank()) {
            line.append(Text.literal(" (installiert: " + current + ")").formatted(Formatting.GRAY));
        }
        if (downloadUrl != null && !downloadUrl.isBlank()) {
            line.append(Text.literal(" "))
                .append(Text.literal("[Herunterladen]")
                        .formatted(Formatting.AQUA, Formatting.BOLD)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent.OpenUrl(URI.create(downloadUrl)))
                                .withHoverEvent(new HoverEvent.ShowText(Text.literal(downloadUrl)))));
        }
        UpdateNotifier.queue(latest, line);
        GMMedic.LOGGER.info("[ApiConnection] Update available: {} (running {})", latest, current);
    }

    /** Answer to a {@code BLOOD_STATUS_REQUEST} for one player. */
    private static void handleBloodStatus(JsonObject obj) {
        String playerName = optString(obj, "playerName");
        if (playerName == null) return;
        boolean canDonate = obj.has("canDonate") && !obj.get("canDonate").isJsonNull()
                && obj.get("canDonate").getAsBoolean();
        BloodDonationManager.getInstance().applyStatus(playerName, canDonate, optLong(obj, "readyAtMs"));
    }

    /**
     * A donation another medic reported. The game server only tells the medic who performed
     * it, so this relay is the only way the rest of the team learns about the cooldown.
     */
    private static void handleBloodSync(JsonObject obj) {
        if (!obj.has("draw") || !obj.get("draw").isJsonObject()) return;
        JsonObject draw = obj.getAsJsonObject("draw");
        String playerName = optString(draw, "playerName");
        if (playerName == null) return;
        long readyAtMs = optLong(draw, "readyAtMs");
        BloodDonationManager.getInstance().applyDraw(playerName, readyAtMs);
        announceBloodDraw(playerName, optString(draw, "medicName"), readyAtMs);
    }

    /** Bulk sync of running cooldowns on connect/duty start — never announced, this is old news. */
    private static void handleBloodList(JsonObject obj) {
        if (!obj.has("draws") || !obj.get("draws").isJsonArray()) return;
        BloodDonationManager manager = BloodDonationManager.getInstance();
        int count = 0;
        for (JsonElement el : obj.getAsJsonArray("draws")) {
            if (!el.isJsonObject()) continue;
            JsonObject draw = el.getAsJsonObject();
            String playerName = optString(draw, "playerName");
            if (playerName == null) continue;
            manager.applyDraw(playerName, optLong(draw, "readyAtMs"));
            count++;
        }
        GMMedic.LOGGER.info("[ApiConnection] Synced {} blood-donation cooldown(s)", count);
    }

    private static void announceBloodDraw(String playerName, String medicName, long readyAtMs) {
        // Display or note switched off — the cooldown was already cached by the caller, so the
        // aimed-at status stays correct either way; only this chat line is skipped.
        HudConfig hud = HudConfig.getInstance();
        if (!hud.isBloodDisplayEnabled() || !hud.isBloodDrawMessageEnabled()) return;
        // The broadcast reaches every connected client; only on-duty medics care.
        if (!EmergencyCallManager.getInstance().isInDuty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        // The reporting medic already saw the game server's own confirmation.
        String me = client.getSession() != null ? client.getSession().getUsername() : null;
        if (medicName != null && me != null && medicName.equalsIgnoreCase(me)) return;

        String text = "[GM-Medic] " + playerName + " hat Blut gespendet"
                + (medicName != null && !medicName.isBlank() ? " (" + medicName + ")" : "")
                + " — wieder spendebereit in " + minutesUntil(readyAtMs) + " Min.";
        client.player.sendMessage(Text.literal(text).formatted(Formatting.AQUA), false);
    }

    private static long minutesUntil(long epochMs) {
        long remainingMs = epochMs - System.currentTimeMillis();
        return Math.max(1L, Math.round(remainingMs / 60_000.0));
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

    private static long optLong(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : 0L;
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
