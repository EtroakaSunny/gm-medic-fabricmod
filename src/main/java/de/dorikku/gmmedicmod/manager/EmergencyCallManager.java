package de.dorikku.gmmedicmod.manager;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.model.EmergencyCall.CallType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    // Callers that were resolved (revived, withdrawn, etc.) while their transmission was still pending.
    // Maps caller name (lowercase) → resolve reason label. Checked & consumed on finalizeCall().
    private final Map<String, String> preResolved = new HashMap<>();

    private static final long PARSING_TIMEOUT_MS = 10_000;

    // Default timer for entangled calls (5 minutes)
    private static final int ENTANGLED_DEFAULT_TIMER_SECONDS = 5 * 60;

    // True when multiple transmissions are arriving simultaneously (interleaved)
    private boolean entangled = false;

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
            preResolved.clear();
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

    public void removeCallByCallerName(String callerName) {
        activeCalls.removeIf(call -> callerMatches(call, callerName));
    }

    /**
     * Removes a specific call instance by object identity.
     * Avoids accidentally removing other calls that share the same caller name (e.g. "Unbekannt").
     */
    public void removeCallInstance(EmergencyCall instance) {
        activeCalls.remove(instance);
    }

    /**
     * Marks a call as resolved (done) — it will be grayed out in the HUD for a few seconds
     * before being auto-removed. The timer is frozen/hidden once resolved.
     *
     * @param callerName the caller to resolve
     * @param reason     display label, e.g. "Wiederbelebt", "Zurückgezogen", "Erreicht"
     */
    public void resolveCall(String callerName, String reason) {
        String normalizedName = normalizeCallerName(callerName);
        String callerKey = callerKey(callerName);
        boolean found = false;
        boolean hasPendingUnnamed = false;

        for (EmergencyCall call : activeCalls) {
            // Track whether there's a still-unnamed pending call that might later receive this name
            if (call.isPending() && ("...".equals(call.getCallerName()) || call.getCallerName() == null)) {
                hasPendingUnnamed = true;
            }
            // Resolve ALL matching calls (there may be duplicates from entanglement)
            if (callerMatches(call, callerName) && !call.isResolved()) {
                call.setResolved(reason);
                found = true;
            }
        }

        // If no active call matched, OR there's a pending unnamed call that might get this name
        // later (setCaller hasn't been called yet), store in preResolved so finalizeCall/setCaller
        // can immediately resolve it once the data arrives.
        if ((!found || hasPendingUnnamed) && callerKey != null) {
            preResolved.put(callerKey, reason);

            // Best-effort: if there are entangled "Unbekannt" placeholder calls that can never
            // be matched by name, resolve the oldest one so it gets cleaned up.
            if (!found) {
                resolveOldestUnknownCall(reason);
            }
        }

        // NEW: Ensure any pending "Unbekannt" placeholder is resolved if a revive happens before data
        for (EmergencyCall call : activeCalls) {
            if (call.isPending() && "Unbekannt".equals(call.getCallerName()) && !call.isResolved()) {
                call.setResolved(reason);
            }
        }

        // If still no call was found, also try to resolve any pending call (even if partially named)
        // as a fallback for cases where the caller name arrives in a different format
        if (!found && callerKey != null) {
            // Prefer to resolve pending calls with unnamed callers ("...") first,
            // then fall back to any pending call. Resolve the most recent one (last in list).
            EmergencyCall targetCall = null;

            // First pass: look for unnamed pending calls (most likely the revive target)
            for (int i = activeCalls.size() - 1; i >= 0; i--) {
                EmergencyCall call = activeCalls.get(i);
                if (call.isPending() && !call.isResolved() && "...".equals(call.getCallerName())) {
                    targetCall = call;
                    break;
                }
            }

            // Second pass: if no unnamed pending found, use any pending call (last in list)
            if (targetCall == null) {
                for (int i = activeCalls.size() - 1; i >= 0; i--) {
                    EmergencyCall call = activeCalls.get(i);
                    if (call.isPending() && !call.isResolved()) {
                        targetCall = call;
                        break;
                    }
                }
            }

            // Resolve the target call if found
            if (targetCall != null) {
                targetCall.setResolved(reason);
                GMMedic.LOGGER.info("[GM-Medic] Resolved pending call by fallback: {} ({})",
                    targetCall.getCallerName(), reason);
            }
        }

        // CRITICAL FIX: If still no call was found anywhere, create a virtual resolved call
        // This ensures resolved calls show up in the HUD immediately, even if the transmission
        // data never arrived (e.g., rapid revive before transmission starts)
        if (!found && normalizedName != null) {
            // Double-check: search more carefully for any existing call with this name
            // including case-insensitive matching
            EmergencyCall existingCall = null;
            for (EmergencyCall call : activeCalls) {
                if (callerMatches(call, callerName)) {
                    existingCall = call;
                    break;
                }
            }

            if (existingCall != null && !existingCall.isResolved()) {
                // Found an existing call that hasn't been resolved yet - resolve it now
                existingCall.setResolved(reason);
                GMMedic.LOGGER.info("[GM-Medic] Resolved existing call by name match: {} ({})",
                    normalizedName, reason);
            } else if (existingCall == null) {
                // No existing call found - create a virtual resolved call that will show in HUD and auto-remove
                EmergencyCall virtualCall = EmergencyCall.pending(CallType.DEATH);
                virtualCall.setCallerName(normalizedName);
                virtualCall.setReason("Unbekannt");  // No reason available for remote revives
                virtualCall.setResolved(reason);      // Set resolution reason (e.g., "Wiederbelebt")
                virtualCall.setPending(false);
                activeCalls.add(virtualCall);
                GMMedic.LOGGER.info("[GM-Medic] Created virtual resolved call: {} — {} ({})",
                    normalizedName, "Unbekannt", reason);
            }
        }
    }

    /**
     * Removes calls that have been in resolved state for longer than the given duration.
     */
    public void removeExpiredResolvedCalls(long maxAgeMs) {
        long now = System.currentTimeMillis();
        activeCalls.removeIf(call -> call.isResolved()
                && call.getResolvedAtMs() > 0
                && now - call.getResolvedAtMs() > maxAgeMs);
    }

    public void assignMedic(String callerName, String medicName) {
        for (EmergencyCall call : activeCalls) {
            if (callerMatches(call, callerName)) {
                call.setAssignedMedic(medicName);
                break;
            }
        }
    }

    /**
     * Clears the assigned medic from a call, making it available to be taken again.
     * Used when a medic cancels while on route.
     */
    public void unassignMedic(String callerName) {
        for (EmergencyCall call : activeCalls) {
            if (callerMatches(call, callerName)) {
                call.setAssignedMedic(null);
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
            if (callerMatches(call, callerName)) {
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
        checkParsingTimeout();
        return state;
    }

    /**
     * Returns true if there is a pending call that already has a real caller name set
     * (i.e. not the placeholder "..."). Used to detect when a new data block header
     * arrives while the current block's data has already been partially received.
     */
    public boolean hasPendingCallerName() {
        return pendingCall != null
                && pendingCall.getCallerName() != null
                && !"...".equals(pendingCall.getCallerName());
    }

    /**
     * If the current transmission has been parsing for longer than PARSING_TIMEOUT_MS,
     * finalize the pending call and mark it as timed out so it grays out in the HUD
     * and is auto-removed.
     */
    private void checkParsingTimeout() {
        if (state == ParsingState.PARSING && System.currentTimeMillis() - stateTimestamp > PARSING_TIMEOUT_MS) {
            EmergencyCall timedOut = finalizeCall();
            if (timedOut != null && !timedOut.isResolved()) {
                timedOut.setResolved("Zeitüberschreitung");
            }
        }
    }

    /**
     * Called from the HUD renderer each frame so timed-out transmissions are caught
     * even when no chat messages are arriving.
     */
    public void timeoutExpiredPendingCalls() {
        checkParsingTimeout();
    }

    /**
     * Called when the pre-message arrives ("Wir haben ... erhalten - ich schicke euch die Daten rüber!").
     * Immediately inserts a pending placeholder call so the HUD shows it right away.
     *
     * @param isDeath true if the pre-message indicates a death call ("Todesmeldung")
     */
    public void startParsing(boolean isDeath) {
        long now = System.currentTimeMillis();

        // If we already started parsing very recently (< 2 s), two transmissions are arriving
        // simultaneously. Mark the situation as entangled so both calls get fallback defaults.
        if (state == ParsingState.PARSING && pendingCall != null && now - stateTimestamp < 2000) {
            entangled = true;
            pendingCall.setEntangled(true);
            // Finalize the existing pending call with pre-resolve check FIRST (before defaults)
            // This ensures revive messages that arrived are honored before entanglement cleanup
            checkPreResolved(pendingCall);
            // Only apply entangled defaults if NOT already resolved
            if (!pendingCall.isResolved()) {
                applyEntangledDefaults(pendingCall);
            }
            pendingCall.setPending(false);
            pendingCall = EmergencyCall.pending(isDeath ? CallType.DEATH : CallType.ECALL);
            pendingCall.setEntangled(true);
            activeCalls.add(pendingCall);
            stateTimestamp = now;
            return;
        }

        // If there's already a dangling pending call, finalize it first
        if (pendingCall != null) {
            // Check pre-resolved FIRST before applying defaults
            checkPreResolved(pendingCall);
            // Only apply entangled defaults if NOT already resolved
            if (!pendingCall.isResolved()) {
                applyEntangledDefaults(pendingCall);
            }
            pendingCall.setPending(false);
        }
        entangled = false;
        pendingCall = EmergencyCall.pending(isDeath ? CallType.DEATH : CallType.ECALL);
        activeCalls.add(pendingCall);
        state = ParsingState.PARSING;
        stateTimestamp = now;

        // NEW: If a preResolved entry exists for "Unbekannt", resolve immediately
        String unknownKey = callerKey("Unbekannt");
        if (unknownKey != null && preResolved.containsKey(unknownKey)) {
            pendingCall.setCallerName("Unbekannt");
            pendingCall.setResolved(preResolved.remove(unknownKey));
        }
    }

    public static String normalizeCallerName(String callerName) {
        if (callerName == null) return null;

        String normalized = callerName.trim().replaceAll("\\s+", " ");
        // Strip common wrappers added by FUNK/system phrasing.
        normalized = normalized.replaceFirst("(?i)^spieler\\s+", "").trim();

        String previous;
        do {
            previous = normalized;
            normalized = normalized.replaceFirst("[.!?]+$", "").trim();
            normalized = normalized.replaceFirst("(?i)\\s*(?:\\(|\\[)?(?:ueber|über|aus)\\s+(?:die|der)\\s+ferne(?:\\)|\\])?\\s*$", "").trim();
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

        String normalizedCaller = normalizeCallerName(caller);
        if (normalizedCaller == null) return;

        // If two different caller names arrive in one entangled parse stream, split them into
        // separate calls so both names are retained while mixed data gets redacted to unknown.
        if (pendingCall.isEntangled()
                && pendingCall.getCallerName() != null
                && !"...".equals(pendingCall.getCallerName())
                && !callerMatches(pendingCall, normalizedCaller)) {
            if (!pendingCall.isResolved()) {
                applyEntangledDefaults(pendingCall);
            }
            pendingCall.setPending(false);

            EmergencyCall split = EmergencyCall.pending(isDeath ? CallType.DEATH : CallType.ECALL);
            split.setEntangled(true);
            activeCalls.add(split);
            pendingCall = split;
            entangled = true;

            GMMedic.LOGGER.info("[GM-Medic] Entangled split detected: preserving both callers (prev={}, next={})",
                    activeCalls.get(activeCalls.size() - 2).getCallerName(), normalizedCaller);
        }

        pendingCall.setCallerName(normalizedCaller);
        pendingCall.setType(isDeath ? CallType.DEATH : CallType.ECALL);
        stateTimestamp = System.currentTimeMillis();

        // Immediately check if this caller was already resolved (e.g. revived) while the
        // call was still unnamed ("..."). This handles rapid revive-before-data scenarios.
        String key = callerKey(normalizedCaller);
        if (key == null) return;
        String preResolveReason = preResolved.remove(key);
        if (preResolveReason != null) {
            pendingCall.setResolved(preResolveReason);
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

    /**
     * Marks the pending call as fully received and ready to display normally.
     */
    public EmergencyCall finalizeCall() {
        if (pendingCall != null) {
            // Check if this caller was already resolved while the transmission was still incoming
            // Do this BEFORE applying entangled defaults, so real resolutions aren't overwritten
            checkPreResolved(pendingCall);

            // If entangled, fill in defaults for any data that might be missing/corrupted
            // But skip if already resolved (don't overwrite real resolution data)
            if ((entangled || pendingCall.isEntangled()) && !pendingCall.isResolved()) {
                pendingCall.setEntangled(true);
                applyEntangledDefaults(pendingCall);
            }

            pendingCall.setPending(false);
            EmergencyCall call = pendingCall;
            pendingCall = null;
            state = ParsingState.IDLE;
            entangled = false;

            // Log finalized call with proper logger
            GMMedic.LOGGER.info("[GM-Medic] Call finalized: {} — {}{}",
                call.getCallerName(),
                call.getReason(),
                call.isResolved() ? " (resolved: " + call.getResolvedReason() + ")" : "");

            return call;
        }
        resetState();
        return null;
    }

    /**
     * Fills in fallback defaults for an entangled call where data may be missing or mixed up.
     * Keeps a parsed caller name if available, but redacts unsafe mixed fields.
     * Caller → keep if known else "Unbekannt", reason → "Unbekannt", location → "Unbekannt",
     * timer → 5 min (if death and no timer set).
     * If the caller ends up as "Unbekannt", immediately marks the call as resolved ("Fehler")
     * so it gets auto-removed quickly by removeExpiredResolvedCalls.
     */
    private void applyEntangledDefaults(EmergencyCall call) {
        boolean wasUnnamed = call.getCallerName() == null || call.getCallerName().equals("...");
        if (wasUnnamed) {
            call.setCallerName("Unbekannt");
        }
        call.setReason("Unbekannt");
        call.clearLocation();
        if (call.getType() == CallType.DEATH && !call.hasTimer()) {
            call.setRemainingSeconds(ENTANGLED_DEFAULT_TIMER_SECONDS);
        }
        // "Unbekannt" calls can never be matched by a real revive/withdraw/logout message.
        // Auto-resolve them so they gray out and get cleaned up within a few seconds.
        if (wasUnnamed && !call.isResolved()) {
            call.setResolved("Fehler");
        }
    }

    /**
     * Checks the preResolved map for a finalized call and immediately resolves it if a
     * revive/withdrawal arrived while the call was still pending/unnamed.
     */
    private void checkPreResolved(EmergencyCall call) {
        if (call == null) return;
        String key = callerKey(call.getCallerName());
        if (key == null) return;
        String preResolveReason = preResolved.remove(key);
        if (preResolveReason != null) {
            call.setResolved(preResolveReason);
        }
    }

    /**
     * Called when a transmission data block ("DATENÜBERMITTLUNG") header arrives while
     * state is IDLE — i.e. an orphaned transmission whose pre-message was consumed by
     * entanglement. Tries to reuse an existing "Unbekannt" entangled call first, so we
     * don't create a duplicate alongside the placeholder that was already finalized.
     * Falls back to creating a new pending call if no reusable call exists.
     */
    public void startOrphanParsing() {
        if (state != ParsingState.IDLE) return;

        // Try to reuse an existing entangled "Unbekannt" call (may already be auto-resolved)
        EmergencyCall reusable = null;
        for (EmergencyCall call : activeCalls) {
            if (call.isEntangled() && "Unbekannt".equals(call.getCallerName()) && !call.isPending()) {
                reusable = call;
                break;
            }
        }

        if (reusable != null) {
            // Reset the placeholder so it can be populated with real data
            reusable.setCallerName("...");
            reusable.setReason("...");
            reusable.setPending(true);
            reusable.clearResolved();
            pendingCall = reusable;
        } else {
            // Default to DEATH since most orphaned transmissions come from rapid death events
            pendingCall = EmergencyCall.pending(CallType.DEATH);
            pendingCall.setEntangled(true);
            activeCalls.add(pendingCall);
        }
        state = ParsingState.PARSING;
        stateTimestamp = System.currentTimeMillis();
        entangled = true;
    }

    /**
     * Resolves the oldest unresolved "Unbekannt" entangled call with the given reason.
     * Best-effort heuristic to clean up placeholder calls that can never be matched by name.
     */
    private void resolveOldestUnknownCall(String reason) {
        for (EmergencyCall call : activeCalls) {
            if (call.isEntangled()
                    && "Unbekannt".equals(call.getCallerName())
                    && !call.isResolved()
                    && !call.isPending()) {
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

