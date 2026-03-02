package de.dorikku.gmmedicmod.api;

import com.google.gson.*;
import de.dorikku.gmmedicmod.model.EmergencyCall;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializes and deserializes {@link EmergencyCall} to/from JSON using Gson
 * (bundled with Minecraft, no extra dependency needed).
 */
public class CallSerializer {

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * Serializes an EmergencyCall to a JSON string for the API.
     */
    public static String toJson(EmergencyCall call) {
        JsonObject obj = new JsonObject();
        obj.addProperty("callerName", call.getCallerName());
        obj.addProperty("reason", call.getReason());
        obj.addProperty("x", call.getX());
        obj.addProperty("y", call.getY());
        obj.addProperty("z", call.getZ());
        obj.addProperty("locationName", call.getLocationName());
        obj.addProperty("type", call.getType().name());
        obj.addProperty("creationTime", call.getCreationTime());
        obj.addProperty("deadlineMs", call.getDeadlineMs());
        obj.addProperty("pending", call.isPending());

        if (call.getAssignedMedic() != null) {
            obj.addProperty("assignedMedic", call.getAssignedMedic());
        }
        if (call.isRejected()) {
            obj.addProperty("rejected", true);
            obj.addProperty("rejectedBy", call.getRejectedBy());
            obj.addProperty("rejectedAtMs", call.getRejectedAtMs());
        }

        return GSON.toJson(obj);
    }

    /**
     * Deserializes a JSON string into an EmergencyCall.
     */
    public static EmergencyCall fromJson(String json) {
        return fromJsonObject(JsonParser.parseString(json).getAsJsonObject());
    }

    /**
     * Deserializes a JSON array string into a list of EmergencyCall objects.
     */
    public static List<EmergencyCall> fromJsonArray(String json) {
        List<EmergencyCall> result = new ArrayList<>();
        JsonArray array = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement element : array) {
            result.add(fromJsonObject(element.getAsJsonObject()));
        }
        return result;
    }

    private static EmergencyCall fromJsonObject(JsonObject obj) {
        String callerName = getStringOr(obj, "callerName", "???");
        String reason = getStringOr(obj, "reason", "???");
        double x = getDoubleOr(obj, "x", 0);
        double y = getDoubleOr(obj, "y", 0);
        double z = getDoubleOr(obj, "z", 0);
        String locationName = getStringOr(obj, "locationName", "");
        String typeStr = getStringOr(obj, "type", "ECALL");
        EmergencyCall.CallType type;
        try {
            type = EmergencyCall.CallType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            type = EmergencyCall.CallType.ECALL;
        }

        EmergencyCall call = new EmergencyCall(callerName, reason, x, y, z, locationName, type);

        if (obj.has("deadlineMs") && !obj.get("deadlineMs").isJsonNull()) {
            long deadlineMs = obj.get("deadlineMs").getAsLong();
            if (deadlineMs > 0) {
                call.setDeadlineMs(deadlineMs);
            }
        }

        if (obj.has("assignedMedic") && !obj.get("assignedMedic").isJsonNull()) {
            call.setAssignedMedic(obj.get("assignedMedic").getAsString());
        }

        if (obj.has("rejected") && obj.get("rejected").getAsBoolean()) {
            String rejectedBy = getStringOr(obj, "rejectedBy", "Unbekannt");
            call.setRejected(rejectedBy);
        }

        if (obj.has("pending") && obj.get("pending").getAsBoolean()) {
            call.setPending(true);
        }

        return call;
    }

    /**
     * Creates a JSON payload for an event (e.g. accept, reject, remove, duty change).
     */
    public static String eventToJson(String eventType, String callerName, String data) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", eventType);
        if (callerName != null) obj.addProperty("callerName", callerName);
        if (data != null) obj.addProperty("data", data);
        return GSON.toJson(obj);
    }

    /**
     * Creates a duty-change payload.
     */
    public static String dutyEventToJson(boolean onDuty, String playerName) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", onDuty ? "duty_on" : "duty_off");
        obj.addProperty("playerName", playerName);
        return GSON.toJson(obj);
    }

    // --- Helpers ---

    private static String getStringOr(JsonObject obj, String key, String def) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return def;
    }

    private static double getDoubleOr(JsonObject obj, String key, double def) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsDouble();
        }
        return def;
    }
}

