package de.dorikku.gmmedicmod.manager;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.model.EmergencyCall.CallType;

import java.util.*;

public class EmergencyCallManager {

    private static final EmergencyCallManager INSTANCE = new EmergencyCallManager();

    public static EmergencyCallManager getInstance() {
        return INSTANCE;
    }

    public enum ParsingState {
        IDLE,
        PARSING
    }

    private record TimestampedValue(String value, long timestampMs) {}

    private boolean inDuty = false;
    private final List<EmergencyCall> activeCalls = new ArrayList<>();
    private EmergencyCall pendingCall = null;
    private final Map<String, TimestampedValue> preResolved = new HashMap<>();
    private final Map<String, TimestampedValue> preAssigned = new HashMap<>();
    private static final long PARSING_TIMEOUT_MS = 10_000L;
    private static final long PRE_BUFFER_EXPIRY_MS = 30_000L;
    private static final long DUPLICATE_WINDOW_MS = 10_000L;
    private static final int ENTANGLED_DEFAULT_TIMER_SECONDS = 300;
    private boolean entangled = false;
    private ParsingState state = ParsingState.IDLE;
    private long stateTimestamp = 0L;

    private EmergencyCallManager() {}

    public boolean isInDuty() { return inDuty; }

    public void setInDuty(boolean inDuty) {
        this.inDuty = inDuty;
        if (!inDuty) {
            activeCalls.clear();
            preResolved.clear();
            preAssigned.clear();
            resetState();
        }
    }

    public List<EmergencyCall> getActiveCalls() {
        return Collections.unmodifiableList(activeCalls);
    }

    public void addCall(EmergencyCall call) {
        activeCalls.add(call);
    }

    public void removeCallByCallerName(String callerName) {
        activeCalls.removeIf(call -> callerMatches(call, callerName));
    }

    public void removeCallInstance(EmergencyCall instance) {
        activeCalls.remove(instance);
    }

    public void resolveCall(String callerName, String reason) {
        evictExpiredBuffers();
        String callerKey = callerKey(callerName);
        boolean found = false;
        boolean hasPendingUnnamed = false;

        for (EmergencyCall call : activeCalls) {
            if (call.isPending() && ("...".equals(call.getCallerName()) || call.getCallerName() == null)) {
                hasPendingUnnamed = true;
            }
            if (callerMatches(call, callerName) && !call.isResolved()) {
                call.setResolved(reason);
                found = true;
            }
        }

        if ((!found || hasPendingUnnamed) && callerKey != null) {
            preResolved.put(callerKey, new TimestampedValue(reason, System.currentTimeMillis()));
            if (!found) {
                resolveOldestUnknownCall(reason);
            }
        }

        if (!found) {
            for (EmergencyCall call : activeCalls) {
                if (call.isPending() && "Unbekannt".equals(call.getCallerName()) && !call.isResolved()) {
                    call.setResolved(reason);
                }
            }
        }

        if (!found && callerKey != null) {
            EmergencyCall targetCall = null;
            for (int i = activeCalls.size() - 1; i >= 0; i--) {
                EmergencyCall call = activeCalls.get(i);
                if (call.isPending() && !call.isResolved() && "...".equals(call.getCallerName())) {
                    targetCall = call;
                    break;
                }
            }
            if (targetCall == null) {
                for (int i = activeCalls.size() - 1; i >= 0; i--) {
                    EmergencyCall call = activeCalls.get(i);
                    if (call.isPending() && !call.isResolved()) {
                        targetCall = call;
                        break;
                    }
                }
            }
            if (targetCall != null) {
                targetCall.setResolved(reason);
                GMMedic.LOGGER.info("[GM-Medic] Resolved pending call by fallback: {} ({})", targetCall.getCallerName(), reason);
            }
        }
    }

    public void removeExpiredResolvedCalls(long maxAgeMs) {
        long now = System.currentTimeMillis();
        activeCalls.removeIf(call -> call.isResolved()
                && call.getResolvedAtMs() > 0L
                && now - call.getResolvedAtMs() > maxAgeMs);
    }

    public void assignMedic(String callerName, String medicName) {
        evictExpiredBuffers();
        for (EmergencyCall call : activeCalls) {
            if (callerMatches(call, callerName) && !call.isPending()) {
                call.setAssignedMedic(medicName);
                GMMedic.LOGGER.info("[GM-Medic] Assigned medic {} to call of {}", medicName, callerName);
                return;
            }
        }
        String key = callerKey(callerName);
        if (key != null) {
            preAssigned.put(key, new TimestampedValue(medicName, System.currentTimeMillis()));
            GMMedic.LOGGER.info("[GM-Medic] preAssigned: {} → {} (no active call yet)", callerName, medicName);
        }
    }

    public void unassignMedic(String callerName) {
        for (EmergencyCall call : activeCalls) {
            if (callerMatches(call, callerName)) {
                call.setAssignedMedic(null);
                break;
            }
        }
    }

    public void rejectCall(String callerName, String medicName) {
        for (EmergencyCall call : activeCalls) {
            if (callerMatches(call, callerName)) {
                call.setRejected(medicName);
                break;
            }
        }
    }

    public void removeExpiredRejectedCalls(long maxAgeMs) {
        long now = System.currentTimeMillis();
        activeCalls.removeIf(call -> call.isRejected()
                && call.getRejectedAtMs() > 0L
                && now - call.getRejectedAtMs() > maxAgeMs);
    }

    public ParsingState getState() {
        checkParsingTimeout();
        return state;
    }

    public boolean hasPendingCallerName() {
        return pendingCall != null
                && pendingCall.getCallerName() != null
                && !"...".equals(pendingCall.getCallerName());
    }

    private void checkParsingTimeout() {
        if (state == ParsingState.PARSING
                && System.currentTimeMillis() - stateTimestamp > PARSING_TIMEOUT_MS) {
            EmergencyCall timedOut = finalizeCall();
            if (timedOut != null && !timedOut.isResolved()) {
                timedOut.setResolved("Zeitüberschreitung");
            }
        }
    }

    public void timeoutExpiredPendingCalls() {
        evictExpiredBuffers();
        checkParsingTimeout();
    }

    private void evictExpiredBuffers() {
        long cutoff = System.currentTimeMillis() - PRE_BUFFER_EXPIRY_MS;
        preResolved.values().removeIf(v -> v.timestampMs() < cutoff);
        preAssigned.values().removeIf(v -> v.timestampMs() < cutoff);
    }

    public void startParsing(boolean isDeath) {
        long now = System.currentTimeMillis();
        CallType type = isDeath ? CallType.DEATH : CallType.ECALL;

        if (state == ParsingState.PARSING && pendingCall != null && now - stateTimestamp < 2000L) {
            entangled = true;
            pendingCall.setEntangled(true);
            checkPreResolved(pendingCall);
            if (!pendingCall.isResolved()) {
                applyEntangledDefaults(pendingCall);
            }
            pendingCall.setPending(false);
            pendingCall = EmergencyCall.pending(type);
            pendingCall.setEntangled(true);
            activeCalls.add(pendingCall);
            stateTimestamp = now;
        } else {
            if (pendingCall != null) {
                checkPreResolved(pendingCall);
                if (!pendingCall.isResolved()) {
                    applyEntangledDefaults(pendingCall);
                }
                pendingCall.setPending(false);
            }
            entangled = false;
            pendingCall = EmergencyCall.pending(type);
            activeCalls.add(pendingCall);
            state = ParsingState.PARSING;
            stateTimestamp = now;
            String unknownKey = callerKey("Unbekannt");
            TimestampedValue unknownPreResolved = unknownKey != null ? preResolved.remove(unknownKey) : null;
            if (unknownPreResolved != null) {
                pendingCall.setCallerName("Unbekannt");
                pendingCall.setResolved(unknownPreResolved.value());
            }
        }
    }

    public static String normalizeCallerName(String callerName) {
        if (callerName == null) return null;
        String normalized = callerName.trim().replaceAll("\\s+", " ");
        String previous;
        do {
            previous = normalized;
            normalized = normalized.replaceFirst("[.!?]+$", "").trim();
            normalized = normalized.replaceFirst("\\s*\\([^)]*\\)\\s*$", "").trim();
        } while (!normalized.equals(previous));
        return normalized.isEmpty() ? null : normalized;
    }

    private static String callerKey(String callerName) {
        String normalized = normalizeCallerName(callerName);
        return normalized != null ? normalized.toLowerCase(Locale.ROOT) : null;
    }

    private static boolean callerMatches(EmergencyCall call, String callerName) {
        String normalizedCall = normalizeCallerName(call.getCallerName());
        String normalizedTarget = normalizeCallerName(callerName);
        return normalizedCall != null && normalizedCall.equalsIgnoreCase(normalizedTarget);
    }

    public void setCaller(String caller, boolean isDeath) {
        if (pendingCall == null) return;
        pendingCall.setCallerName(normalizeCallerName(caller));
        pendingCall.setType(isDeath ? CallType.DEATH : CallType.ECALL);
        stateTimestamp = System.currentTimeMillis();
        String key = callerKey(caller);
        if (key != null) {
            TimestampedValue tv = preResolved.remove(key);
            if (tv != null) {
                pendingCall.setResolved(tv.value());
            }
            checkPreAssigned(pendingCall);
        }
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

    public EmergencyCall finalizeCall() {
        if (pendingCall == null) {
            resetState();
            return null;
        }
        checkPreResolved(pendingCall);
        checkPreAssigned(pendingCall);
        if ((entangled || pendingCall.isEntangled()) && !pendingCall.isResolved()) {
            if (isCallComplete(pendingCall)) {
                if (isDuplicateTransmission(pendingCall)) {
                    GMMedic.LOGGER.info("[GM-Medic] Discarded duplicate transmission for {}", pendingCall.getCallerName());
                    activeCalls.remove(pendingCall);
                    pendingCall = null;
                    state = ParsingState.IDLE;
                    entangled = false;
                    return null;
                }
                pendingCall.setEntangled(false);
            } else {
                pendingCall.setEntangled(true);
                applyEntangledDefaults(pendingCall);
            }
        }
        pendingCall.setPending(false);
        EmergencyCall call = pendingCall;
        pendingCall = null;
        state = ParsingState.IDLE;
        entangled = false;
        GMMedic.LOGGER.info("[GM-Medic] Call finalized: {} — {}{}", call.getCallerName(), call.getReason(),
                call.isResolved() ? " (resolved: " + call.getResolvedReason() + ")" : "");
        return call;
    }

    private void applyEntangledDefaults(EmergencyCall call) {
        boolean wasUnnamed = call.getCallerName() == null || "...".equals(call.getCallerName());
        if (wasUnnamed) call.setCallerName("Unbekannt");
        call.setReason("Unbekannt");
        call.clearLocation();
        if (call.getType() == CallType.DEATH && !call.hasTimer()) {
            call.setRemainingSeconds(ENTANGLED_DEFAULT_TIMER_SECONDS);
        }
        if (wasUnnamed && !call.isResolved()) {
            call.setResolved("Fehler");
        }
    }

    private void checkPreResolved(EmergencyCall call) {
        if (call == null) return;
        String key = callerKey(call.getCallerName());
        if (key != null) {
            TimestampedValue tv = preResolved.remove(key);
            if (tv != null) {
                call.setResolved(tv.value());
            }
        }
    }

    private void checkPreAssigned(EmergencyCall call) {
        if (call == null) return;
        String key = callerKey(call.getCallerName());
        if (key != null) {
            TimestampedValue tv = preAssigned.remove(key);
            if (tv != null) {
                call.setAssignedMedic(tv.value());
                GMMedic.LOGGER.info("[GM-Medic] Applied preAssigned medic {} to {}", tv.value(), call.getCallerName());
            }
        }
    }

    private boolean isCallComplete(EmergencyCall call) {
        return call.getCallerName() != null && !"...".equals(call.getCallerName())
                && call.getReason() != null && !"...".equals(call.getReason());
    }

    private boolean isDuplicateTransmission(EmergencyCall candidate) {
        String key = callerKey(candidate.getCallerName());
        if (key == null) return false;
        long now = System.currentTimeMillis();
        for (EmergencyCall existing : activeCalls) {
            if (existing != candidate
                    && !existing.isPending()
                    && !existing.isResolved()
                    && callerMatches(existing, candidate.getCallerName())
                    && now - existing.getCreationTime() < DUPLICATE_WINDOW_MS) {
                return true;
            }
        }
        return false;
    }

    public void startOrphanParsing() {
        if (state == ParsingState.IDLE) {
            EmergencyCall reusable = null;
            for (EmergencyCall call : activeCalls) {
                if (call.isEntangled() && "Unbekannt".equals(call.getCallerName()) && !call.isPending()) {
                    reusable = call;
                    break;
                }
            }
            if (reusable != null) {
                reusable.setCallerName("...");
                reusable.setReason("...");
                reusable.setPending(true);
                reusable.clearResolved();
                pendingCall = reusable;
            } else {
                pendingCall = EmergencyCall.pending(CallType.DEATH);
                pendingCall.setEntangled(true);
                activeCalls.add(pendingCall);
            }
            state = ParsingState.PARSING;
            stateTimestamp = System.currentTimeMillis();
            entangled = true;
        }
    }

    private void resolveOldestUnknownCall(String reason) {
        for (EmergencyCall call : activeCalls) {
            if (call.isEntangled() && "Unbekannt".equals(call.getCallerName())
                    && !call.isResolved() && !call.isPending()) {
                call.setResolved(reason);
                break;
            }
        }
    }

    public void resetState() {
        if (pendingCall != null) {
            activeCalls.remove(pendingCall);
            pendingCall = null;
        }
        state = ParsingState.IDLE;
        entangled = false;
    }
}
