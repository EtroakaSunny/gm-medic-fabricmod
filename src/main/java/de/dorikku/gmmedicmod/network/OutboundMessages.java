package de.dorikku.gmmedicmod.network;

import com.google.gson.JsonObject;
import de.dorikku.gmmedicmod.model.EmergencyCall;

public final class OutboundMessages {

    private OutboundMessages() {}

    public static String auth(String token, String username) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "AUTH");
        obj.addProperty("token", token);
        obj.addProperty("username", username);
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }

    public static String dutyOn(String username) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "DUTY_ON");
        obj.addProperty("username", username);
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }

    public static String dutyOff(String username) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "DUTY_OFF");
        obj.addProperty("username", username);
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }

    public static String callNew(EmergencyCall call) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "CALL_NEW");
        obj.addProperty("callId", call.getCallId());
        obj.addProperty("callerName", call.getCallerName());
        obj.addProperty("callType", call.getType().name());
        obj.addProperty("reason", call.getReason());
        obj.addProperty("x", call.getX());
        obj.addProperty("y", call.getY());
        obj.addProperty("z", call.getZ());
        obj.addProperty("locationName", call.getLocationName() != null ? call.getLocationName() : "");
        obj.addProperty("deadlineMs", call.getDeadlineMs());
        obj.addProperty("resolved", call.isResolved());
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }

    public static String callAssigned(EmergencyCall call) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "CALL_ASSIGNED");
        obj.addProperty("callId", call.getCallId());
        obj.addProperty("callerName", call.getCallerName());
        obj.addProperty("medicName", call.getAssignedMedic());
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }

    public static String callResolved(EmergencyCall call) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "CALL_RESOLVED");
        obj.addProperty("callId", call.getCallId());
        obj.addProperty("callerName", call.getCallerName());
        obj.addProperty("resolveReason", call.getResolvedReason() != null ? call.getResolvedReason() : "");
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }

    public static String callRejected(EmergencyCall call) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "CALL_REJECTED");
        obj.addProperty("callId", call.getCallId());
        obj.addProperty("callerName", call.getCallerName());
        obj.addProperty("rejectedBy", call.getRejectedBy() != null ? call.getRejectedBy() : "");
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }

    public static String locationUpdate(String username, double x, double y, double z, boolean driving) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "LOCATION_UPDATE");
        obj.addProperty("username", username);
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        obj.addProperty("driving", driving);
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }

    /**
     * Reports that this medic drew and donated a player's blood. The game server tells
     * only the acting medic, so this client is the single source for the event — the API
     * server starts the player's cooldown from it and relays it to every other medic.
     */
    public static String bloodDrawn(String medicName, String playerName) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "BLOOD_DRAWN");
        obj.addProperty("medicName", medicName);
        obj.addProperty("playerName", playerName);
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }

    /** Asks whether a player may donate blood again; answered with {@code BLOOD_STATUS}. */
    public static String bloodStatusRequest(String playerName) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "BLOOD_STATUS_REQUEST");
        obj.addProperty("playerName", playerName);
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }

    public static String alarmTriggered(String username, String alarmName) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "ALARM_TRIGGERED");
        obj.addProperty("username", username);
        obj.addProperty("alarmName", alarmName);
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }

    public static String alarmEnded(String username) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "ALARM_ENDED");
        obj.addProperty("username", username);
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }

    public static String ping() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "PING");
        obj.addProperty("timestamp", System.currentTimeMillis());
        return obj.toString();
    }
}
