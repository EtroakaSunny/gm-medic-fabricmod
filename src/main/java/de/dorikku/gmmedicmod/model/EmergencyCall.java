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
    private boolean pending;
    private long deadlineMs = -1L;
    private String assignedMedic;
    private boolean rejected = false;
    private long rejectedAtMs = -1L;
    private String rejectedBy;
    private boolean resolved = false;
    private long resolvedAtMs = -1L;
    private String resolvedReason;
    private boolean entangled = false;

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

    public static EmergencyCall pending(CallType type) {
        EmergencyCall c = new EmergencyCall("...", "...", 0.0, 0.0, 0.0, "", type);
        c.pending = true;
        return c;
    }

    public String getCallerName() { return callerName; }
    public void setCallerName(String callerName) { this.callerName = callerName; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }

    public void setLocation(double x, double y, double z, String locationName) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.locationName = locationName;
    }

    public void clearLocation() {
        this.x = Double.NaN;
        this.y = Double.NaN;
        this.z = Double.NaN;
        this.locationName = null;
    }

    public boolean hasKnownLocation() {
        return !Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z);
    }

    public String getLocationName() { return locationName; }

    public CallType getType() { return type; }
    public void setType(CallType type) { this.type = type; }

    public long getCreationTime() { return creationTime; }

    public boolean isPending() { return pending; }
    public void setPending(boolean pending) { this.pending = pending; }

    public void setRemainingSeconds(int seconds) {
        this.deadlineMs = System.currentTimeMillis() + (long) seconds * 1000L;
    }

    public int getRemainingSeconds() {
        if (deadlineMs < 0L) return -1;
        long remaining = (deadlineMs - System.currentTimeMillis()) / 1000L;
        return (int) Math.max(0L, remaining);
    }

    public boolean hasTimer() { return deadlineMs >= 0L; }
    public long getDeadlineMs() { return deadlineMs; }

    public String getAssignedMedic() { return assignedMedic; }
    public void setAssignedMedic(String assignedMedic) { this.assignedMedic = assignedMedic; }
    public boolean isAccepted() { return assignedMedic != null; }

    public String getLocationString() {
        if (!hasKnownLocation()) return "Unbekannt";
        String coords = String.format("X: %.0f, Y: %.0f, Z: %.0f", x, y, z);
        return locationName != null && !locationName.isEmpty() ? coords + " (" + locationName + ")" : coords;
    }

    public boolean isRejected() { return rejected; }

    public void setRejected(String byMedic) {
        this.rejected = true;
        this.rejectedAtMs = System.currentTimeMillis();
        this.rejectedBy = byMedic;
    }

    public long getRejectedAtMs() { return rejectedAtMs; }
    public String getRejectedBy() { return rejectedBy; }

    public boolean isResolved() { return resolved; }

    public void setResolved(String reason) {
        this.resolved = true;
        this.resolvedAtMs = System.currentTimeMillis();
        this.resolvedReason = reason;
    }

    public long getResolvedAtMs() { return resolvedAtMs; }
    public String getResolvedReason() { return resolvedReason; }

    public void clearResolved() {
        this.resolved = false;
        this.resolvedAtMs = -1L;
        this.resolvedReason = null;
    }

    public boolean isEntangled() { return entangled; }
    public void setEntangled(boolean entangled) { this.entangled = entangled; }
}
