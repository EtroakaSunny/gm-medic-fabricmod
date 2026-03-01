package de.dorikku.gmmedicmod.model;

public class EmergencyCall {

    public enum CallType {
        ECALL,
        DEATH
    }

    private String callerName;
    private String reason;
    private double x;
    private double y;
    private double z;
    private String locationName;
    private CallType type;
    private final long creationTime;

    // True while the server is still sending the remaining lines of the transmission block
    private boolean pending;

    // For DEATH calls: absolute epoch ms when the revival window expires. -1 = no timer.
    private long deadlineMs = -1;

    private String assignedMedic;

    // Rejection state: when a medic rejects the call, we keep it visible for 10 s
    private boolean rejected = false;
    private long rejectedAtMs = -1;
    private String rejectedBy;

    /** Creates a fully resolved call (all data known). */
    public EmergencyCall(String callerName, String reason, double x, double y, double z, String locationName, CallType type) {
        this.callerName = callerName;
        this.reason = reason;
        this.x = x;
        this.y = y;
        this.z = z;
        this.locationName = locationName;
        this.type = type;
        this.creationTime = System.currentTimeMillis();
        this.pending = false;
        this.assignedMedic = null;
    }

    /** Creates a placeholder call shown immediately when the header line arrives. */
    public static EmergencyCall pending() {
        EmergencyCall c = new EmergencyCall("...", "...", 0, 0, 0, "", CallType.ECALL);
        c.pending = true;
        return c;
    }

    public String getCallerName() {
        return callerName;
    }

    public void setCallerName(String callerName) {
        this.callerName = callerName;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public void setLocation(double x, double y, double z, String locationName) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.locationName = locationName;
    }

    public String getLocationName() {
        return locationName;
    }

    public CallType getType() {
        return type;
    }

    public void setType(CallType type) {
        this.type = type;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public boolean isPending() {
        return pending;
    }

    public void setPending(boolean pending) {
        this.pending = pending;
    }

    /** Sets the revival countdown from the "Verbleibende Zeit" line (DEATH calls only). */
    public void setRemainingSeconds(int seconds) {
        this.deadlineMs = System.currentTimeMillis() + (seconds * 1000L);
    }

    /**
     * Returns how many seconds are left on the revival timer, or -1 if no timer is set.
     * Returns 0 once the timer has expired.
     */
    public int getRemainingSeconds() {
        if (deadlineMs < 0) return -1;
        long remaining = (deadlineMs - System.currentTimeMillis()) / 1000L;
        return (int) Math.max(0, remaining);
    }

    public boolean hasTimer() {
        return deadlineMs >= 0;
    }

    public long getDeadlineMs() {
        return deadlineMs;
    }

    public String getAssignedMedic() {
        return assignedMedic;
    }

    public void setAssignedMedic(String assignedMedic) {
        this.assignedMedic = assignedMedic;
    }

    public boolean isAccepted() {
        return assignedMedic != null;
    }

    public String getLocationString() {
        String coords = String.format("X: %.0f, Y: %.0f, Z: %.0f", x, y, z);
        if (locationName != null && !locationName.isEmpty()) {
            return coords + " (" + locationName + ")";
        }
        return coords;
    }

    // --- Rejection state ---

    public boolean isRejected() {
        return rejected;
    }

    public void setRejected(String byMedic) {
        this.rejected = true;
        this.rejectedAtMs = System.currentTimeMillis();
        this.rejectedBy = byMedic;
    }

    public long getRejectedAtMs() {
        return rejectedAtMs;
    }

    public String getRejectedBy() {
        return rejectedBy;
    }
}

