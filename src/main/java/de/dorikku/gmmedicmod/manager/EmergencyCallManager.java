package de.dorikku.gmmedicmod.manager;

import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.model.EmergencyCall.CallType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class EmergencyCallManager {

    private static final EmergencyCallManager INSTANCE = new EmergencyCallManager();

    public static EmergencyCallManager getInstance() {
        return INSTANCE;
    }

    // Duty status - HUD only shows when on duty
    private boolean inDuty = false;

    // Active calls list (may contain one pending call at the end while transmission is ongoing)
    private final List<EmergencyCall> activeCalls = new ArrayList<>();

    // The call currently being built from an incoming transmission block
    private EmergencyCall pendingCall = null;

    private static final long PARSING_TIMEOUT_MS = 10_000;

    // Kept for ChatMessageHandler state checks
    public enum ParsingState {
        IDLE,
        PARSING  // A transmission block is currently being received
    }

    private ParsingState state = ParsingState.IDLE;
    private long stateTimestamp = 0;

    private EmergencyCallManager() {}

    // --- Duty status ---

    public boolean isInDuty() {
        return inDuty;
    }

    public void setInDuty(boolean inDuty) {
        this.inDuty = inDuty;
        if (!inDuty) {
            activeCalls.clear();
            resetState();
        }
    }

    // --- Active calls management ---

    public List<EmergencyCall> getActiveCalls() {
        return Collections.unmodifiableList(activeCalls);
    }

    public void addCall(EmergencyCall call) {
        activeCalls.add(call);
    }

    /**
     * Merges a remotely-received call into the local list.
     * If no call with the same caller name exists, it is added.
     * If it does exist, only state changes (medic, rejection) are applied.
     * This is called from the API polling loop on the client thread.
     */
    public void mergeRemoteCall(EmergencyCall remote) {
        if (remote == null || remote.getCallerName() == null) return;

        for (EmergencyCall local : activeCalls) {
            if (local.getCallerName().equalsIgnoreCase(remote.getCallerName())) {
                // Update state on existing call if changed remotely
                if (remote.isAccepted() && !local.isAccepted()) {
                    local.setAssignedMedic(remote.getAssignedMedic());
                }
                if (remote.isRejected() && !local.isRejected()) {
                    local.setRejected(remote.getRejectedBy());
                }
                return;
            }
        }

        // Not found locally — add it (skip if pending, those are mid-transmission)
        if (!remote.isPending()) {
            activeCalls.add(remote);
        }
    }

    public void removeCallByCallerName(String callerName) {
        activeCalls.removeIf(call -> call.getCallerName().equalsIgnoreCase(callerName));
    }

    public void assignMedic(String callerName, String medicName) {
        for (EmergencyCall call : activeCalls) {
            if (call.getCallerName().equalsIgnoreCase(callerName)) {
                call.setAssignedMedic(medicName);
                break;
            }
        }
    }

    /**
     * Marks a call as rejected by a medic. The call stays in the list for 10 seconds
     * so the HUD can display "Zurückgewiesen" before it is auto-removed.
     */
    public void rejectCall(String callerName, String medicName) {
        for (EmergencyCall call : activeCalls) {
            if (call.getCallerName().equalsIgnoreCase(callerName)) {
                call.setRejected(medicName);
                break;
            }
        }
    }

    /**
     * Removes calls that have been in rejected state for longer than the given duration.
     */
    public void removeExpiredRejectedCalls(long maxAgeMs) {
        long now = System.currentTimeMillis();
        activeCalls.removeIf(call -> call.isRejected()
                && call.getRejectedAtMs() > 0
                && now - call.getRejectedAtMs() > maxAgeMs);
    }

    // --- Transmission parsing ---

    public ParsingState getState() {
        if (state == ParsingState.PARSING && System.currentTimeMillis() - stateTimestamp > PARSING_TIMEOUT_MS) {
            // Timed out - finalize whatever we have so the HUD doesn't stay "pending" forever
            finalizeCall();
        }
        return state;
    }

    /**
     * Called when the DATENÜBERMITTLUNG header line arrives.
     * Immediately inserts a pending placeholder call so the HUD shows it right away.
     */
    public void startParsing() {
        // If there's already a dangling pending call, finalize it first
        if (pendingCall != null) {
            pendingCall.setPending(false);
        }
        pendingCall = EmergencyCall.pending();
        activeCalls.add(pendingCall);
        state = ParsingState.PARSING;
        stateTimestamp = System.currentTimeMillis();
    }

    public void setCaller(String caller, boolean isDeath) {
        if (pendingCall == null) return;
        pendingCall.setCallerName(caller);
        pendingCall.setType(isDeath ? CallType.DEATH : CallType.ECALL);
        stateTimestamp = System.currentTimeMillis();
    }

    public void setReason(String reason) {
        if (pendingCall == null) return;
        pendingCall.setReason(reason);
        stateTimestamp = System.currentTimeMillis();
    }

    public void setRemainingTime(int seconds) {
        if (pendingCall == null) return;
        pendingCall.setRemainingSeconds(seconds);
        stateTimestamp = System.currentTimeMillis();
    }

    public void setLocation(double x, double y, double z, String locationName) {
        if (pendingCall == null) return;
        pendingCall.setLocation(x, y, z, locationName);
        stateTimestamp = System.currentTimeMillis();
    }

    /**
     * Marks the pending call as fully received and ready to display normally.
     */
    public EmergencyCall finalizeCall() {
        if (pendingCall != null) {
            pendingCall.setPending(false);
            EmergencyCall call = pendingCall;
            pendingCall = null;
            state = ParsingState.IDLE;
            return call;
        }
        resetState();
        return null;
    }

    public void resetState() {
        if (pendingCall != null) {
            activeCalls.remove(pendingCall);
            pendingCall = null;
        }
        state = ParsingState.IDLE;
    }
}

