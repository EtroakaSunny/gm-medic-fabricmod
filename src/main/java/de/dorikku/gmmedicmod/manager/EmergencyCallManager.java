package de.dorikku.gmmedicmod.manager;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.model.EmergencyCall.CallType;

import java.util.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EmergencyCallManager {

    public interface CallEventListener {
        void onCallNew(EmergencyCall call);
        void onCallAssigned(EmergencyCall call);
        void onCallResolved(EmergencyCall call);
        void onCallRejected(EmergencyCall call);
        void onDutyChanged(boolean inDuty);
    }

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
    /** Player key -> expiry epoch ms for temporary keyword highlights (e.g. "heal"/"low" in chat). */
    private final Map<String, Long> keywordHighlights = new ConcurrentHashMap<>();
    private static final long KEYWORD_HIGHLIGHT_MS = 30_000L;
    private static final long PARSING_TIMEOUT_MS = 10_000L;
    private static final long PRE_BUFFER_EXPIRY_MS = 30_000L;
    private static final int ENTANGLED_DEFAULT_TIMER_SECONDS = 300;
    private boolean entangled = false;
    private ParsingState state = ParsingState.IDLE;
    private long stateTimestamp = 0L;
    private CallEventListener eventListener;

    private EmergencyCallManager() {}

    public void setEventListener(CallEventListener listener) {
        this.eventListener = listener;
    }

    public boolean isInDuty() { return inDuty; }

    public void setInDuty(boolean inDuty) {
        this.inDuty = inDuty;
        if (eventListener != null) eventListener.onDutyChanged(inDuty);
        if (!inDuty) {
            activeCalls.clear();
            preResolved.clear();
            preAssigned.clear();
            keywordHighlights.clear();
            resetState();
        }
    }

    /**
     * Flag a player to be highlighted for {@value #KEYWORD_HIGHLIGHT_MS} ms because they wrote a
     * keyword like "heal"/"low" in chat. Re-writing the keyword refreshes the timer.
     */
    public void addKeywordHighlight(String playerName) {
        String key = callerKey(playerName);
        if (key == null) return;
        keywordHighlights.put(key, System.currentTimeMillis() + KEYWORD_HIGHLIGHT_MS);
    }

    /** Clears a player's keyword highlight, e.g. once a medic has applied a bandage to them. */
    public void removeKeywordHighlight(String playerName) {
        String key = callerKey(playerName);
        if (key == null) return;
        keywordHighlights.remove(key);
    }

    /** Normalized lowercase keys of players whose keyword highlight is still active (expired ones pruned). */
    public Set<String> getKeywordHighlightTargets() {
        long now = System.currentTimeMillis();
        keywordHighlights.values().removeIf(expiry -> expiry <= now);
        return new HashSet<>(keywordHighlights.keySet());
    }

    public List<EmergencyCall> getActiveCalls() {
        return Collections.unmodifiableList(activeCalls);
    }

    public EmergencyCall findByCallId(String callId) {
        if (callId == null) return null;
        for (EmergencyCall call : activeCalls) {
            if (callId.equals(call.getCallId())) return call;
        }
        return null;
    }

    /**
     * Add or update a call that originated on another client and was relayed by
     * the server (sync-on-join or live CALL_SYNC). Does not fire listener events,
     * so it never echoes back to the server.
     */
    public EmergencyCall upsertRemoteCall(String callId, CallType type, String caller, String reason,
                                          double x, double y, double z, String locationName,
                                          long deadlineMs, String assignedMedic, String suggestedMedic,
                                          boolean resolved, String resolveReason, String rejectedBy) {
        if (callId == null) return null;
        EmergencyCall call = findByCallId(callId);
        if (call == null) {
            // The same real transmission may already be tracked locally under a
            // different callId — this client parsed the chat line itself and minted
            // its own random UUID before the server told it another medic's report
            // won. Adopt the server's callId onto that local call instead of adding
            // a second HUD row for the same emergency.
            call = findMergeCandidate(caller, type);
            if (call != null) {
                call.setCallId(callId);
                call.setRemote(true);
            }
        }
        if (call == null) {
            call = new EmergencyCall(caller, reason, x, y, z, locationName, type);
            call.setCallId(callId);
            call.setRemote(true);
            if (deadlineMs > 0L) call.setDeadlineMs(deadlineMs);
            if (locationName == null && Double.isNaN(x)) call.clearLocation();
            activeCalls.add(call);
        } else {
            if (caller != null) call.setCallerName(caller);
            if (reason != null) call.setReason(reason);
            call.setType(type);
            if (locationName != null || !Double.isNaN(x)) call.setLocation(x, y, z, locationName);
        }
        if (assignedMedic != null) call.setAssignedMedic(assignedMedic);
        call.setSuggestedMedic(suggestedMedic);
        if (rejectedBy != null && !call.isRejected()) call.setRejected(rejectedBy);
        if (resolved && !call.isResolved()) {
            call.setResolved(resolveReason != null ? resolveReason : "Erledigt");
        }
        return call;
    }

    /**
     * Finds a locally tracked, active call of the same type for this caller that a remote
     * update (a different callId) can be merged into, instead of creating a duplicate HUD row.
     */
    private EmergencyCall findMergeCandidate(String caller, CallType type) {
        String key = callerKey(caller);
        if (key == null) return null;
        for (EmergencyCall existing : activeCalls) {
            if (!existing.isPending() && !existing.isResolved()
                    && existing.getType() == type
                    && callerMatches(existing, caller)) {
                return existing;
            }
        }
        return null;
    }

    public void addCall(EmergencyCall call) {
        activeCalls.add(call);
    }

    /**
     * Finds this client's own active, unresolved local-only call for a caller (see
     * {@link EmergencyCall#isLocalOnly()}) — used to avoid offering/creating a duplicate
     * and to resolve it directly (bypassing {@link #resolveCall}, which would fire the
     * {@link CallEventListener} and sync it to the server).
     */
    public EmergencyCall findActiveLocalOnlyCall(String callerName) {
        for (EmergencyCall call : activeCalls) {
            if (call.isLocalOnly() && !call.isResolved() && callerMatches(call, callerName)) {
                return call;
            }
        }
        return null;
    }

    public void removeCallByCallerName(String callerName) {
        activeCalls.removeIf(call -> callerMatches(call, callerName));
    }

    public void removeCallInstance(EmergencyCall instance) {
        activeCalls.remove(instance);
    }

    public void removeByCallId(String callId) {
        if (callId == null) return;
        activeCalls.removeIf(call -> callId.equals(call.getCallId()));
    }

    private void fireResolvedOnce(EmergencyCall call) {
        if (call == null || call.isPending() || call.isApiNotifiedResolved() || eventListener == null) return;
        call.setApiNotifiedResolved(true);
        eventListener.onCallResolved(call);
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
                fireResolvedOnce(call);
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
                    fireResolvedOnce(call);
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
                fireResolvedOnce(targetCall);
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
                if (eventListener != null) eventListener.onCallAssigned(call);
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
                if (eventListener != null) eventListener.onCallRejected(call);
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
                fireResolvedOnce(timedOut);
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

        // Duplication remover: if this caller already has an active, unresolved call of the
        // same type on the HUD (DEATH and ECALL are kept distinct), drop the new transmission
        // instead of showing a second identical entry.
        if (!pendingCall.isResolved() && isCallComplete(pendingCall) && isDuplicateTransmission(pendingCall)) {
            GMMedic.LOGGER.info("[GM-Medic] Discarded duplicate {} call for {}",
                    pendingCall.getType(), pendingCall.getCallerName());
            activeCalls.remove(pendingCall);
            pendingCall = null;
            state = ParsingState.IDLE;
            entangled = false;
            return null;
        }

        if ((entangled || pendingCall.isEntangled()) && !pendingCall.isResolved()) {
            if (isCallComplete(pendingCall)) {
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
        if (call.getCallId() == null) call.setCallId(UUID.randomUUID().toString());
        if (eventListener != null) eventListener.onCallNew(call);
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

    /**
     * True when {@code candidate} would be a duplicate of a call already on the HUD: the same
     * caller with an active (non-pending, unresolved) call of the <em>same type</em>. DEATH and
     * ECALL are treated as distinct, so a death and an emergency call from one player coexist.
     */
    private boolean isDuplicateTransmission(EmergencyCall candidate) {
        if (candidate == null) return false;
        String key = callerKey(candidate.getCallerName());
        if (key == null) return false;
        for (EmergencyCall existing : activeCalls) {
            if (existing != candidate
                    && !existing.isPending()
                    && !existing.isResolved()
                    && existing.getType() == candidate.getType()
                    && callerMatches(existing, candidate.getCallerName())) {
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
