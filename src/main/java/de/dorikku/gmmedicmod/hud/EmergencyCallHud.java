package de.dorikku.gmmedicmod.hud;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.List;

public class EmergencyCallHud {

    private static final int PADDING = 6;
    private static final int LINE_HEIGHT = 11;
    private static final int MARGIN = 10;
    private static final int ENTRY_SPACING = 4;
    private static boolean loggedHudActivation = false;

    // Colors
    private static final int BG_COLOR = 0x90000000;           // Semi-transparent black
    private static final int HEADER_COLOR = 0xFFFF5555;        // Red header
    private static final int ECALL_COLOR = 0xFFFFAA00;         // Orange/yellow for normal calls
    private static final int DEATH_COLOR = 0xFFFF5555;         // Red for death calls
    private static final int ACCEPTED_COLOR = 0xFF55FF55;      // Green for accepted
    private static final int INFO_COLOR = 0xFFAAAAAA;          // Gray for info text
    private static final int MEDIC_COLOR = 0xFF55FFFF;         // Cyan for medic name
    private static final int NO_CALLS_COLOR = 0xFF888888;      // Dark gray
    private static final int PENDING_COLOR = 0xFFFFFF55;       // Yellow for "waiting" state
    private static final int REJECTED_COLOR = 0xFFFF5555;      // Red for rejected calls

    // Death timer colors
    private static final int TIMER_OK_COLOR    = 0xFF55FF55;   // Green:      > 3 min
    private static final int TIMER_WARN_COLOR  = 0xFFFFAA00;   // Orange:     1-3 min
    private static final int TIMER_URGENT_COLOR= 0xFFFFFF55;   // Yellow:     30-60 s
    private static final int TIMER_CRIT_COLOR  = 0xFFFFFFFF;   // White flash: < 30 s
    private static final int TIMER_CRIT_FLASH_COLOR = 0xFFFF5555; // Red for critical timer flash

    // Location display alternates between coords and name every 7 seconds
    private static final long LOCATION_TOGGLE_MS = 7_000;

    public static void render(DrawContext drawContext, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        // Only show HUD when on duty
        if (!EmergencyCallManager.getInstance().isInDuty()) return;

        if (!loggedHudActivation) {
            loggedHudActivation = true;
            var mgr = EmergencyCallManager.getInstance();
            GMMedic.LOGGER.info("HUD render activated - duty={}, activeCalls={}", mgr.isInDuty(), mgr.getActiveCalls().size());
        }

        TextRenderer textRenderer = client.textRenderer;
        List<EmergencyCall> calls = EmergencyCallManager.getInstance().getActiveCalls();

        // Remove death calls whose timer expired more than 10 seconds ago
        calls.stream()
                .filter(c -> c.hasTimer() && c.getDeadlineMs() > 0
                        && System.currentTimeMillis() > c.getDeadlineMs() + 10_000L)
                .toList()
                .forEach(c -> EmergencyCallManager.getInstance().removeCallByCallerName(c.getCallerName()));

        // Remove rejected calls after 10 seconds
        EmergencyCallManager.getInstance().removeExpiredRejectedCalls(10_000L);

        int screenWidth = client.getWindow().getScaledWidth();

        // Calculate panel dimensions
        int panelWidth = 200;
        int panelX = screenWidth - panelWidth - MARGIN;
        int panelY = MARGIN;

        // Header
        String header = "\u00a7c\u00a7l\ud83d\ude91 Notrufe (" + calls.size() + ")";
        int currentY = panelY;

        if (calls.isEmpty()) {
            // Always show the "no active calls" panel so the user sees duty is active
            int panelHeight = PADDING * 2 + LINE_HEIGHT * 2;
            drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, BG_COLOR);

            currentY += PADDING;
            drawContext.drawText(textRenderer, header, panelX + PADDING, currentY, HEADER_COLOR, true);
            currentY += LINE_HEIGHT;
            drawContext.drawText(textRenderer, "Keine aktiven Notrufe", panelX + PADDING, currentY, NO_CALLS_COLOR, true);
            return;
        }

        // Calculate total height
        int totalHeight = PADDING + LINE_HEIGHT + PADDING; // header + spacing
        for (int i = 0; i < calls.size(); i++) {
            totalHeight += calculateEntryHeight(calls.get(i));
            if (i < calls.size() - 1) {
                totalHeight += ENTRY_SPACING;
            }
        }
        totalHeight += PADDING; // bottom padding

        // Draw background
        drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + totalHeight, BG_COLOR);

        // Draw header
        currentY += PADDING;
        drawContext.drawText(textRenderer, header, panelX + PADDING, currentY, HEADER_COLOR, true);
        currentY += LINE_HEIGHT + PADDING;

        // Draw separator line
        drawContext.fill(panelX + PADDING, currentY - 2, panelX + panelWidth - PADDING, currentY - 1, 0xFF555555);

        // true = show coords, false = show location name; flips every 7 s for all calls at once
        boolean showCoords = (System.currentTimeMillis() / LOCATION_TOGGLE_MS) % 2 == 0;

        // Draw each call
        for (int i = 0; i < calls.size(); i++) {
            EmergencyCall call = calls.get(i);
            currentY = drawCallEntry(drawContext, textRenderer, call, panelX, currentY, panelWidth, i + 1, showCoords);
            if (i < calls.size() - 1) {
                currentY += ENTRY_SPACING;
                // Draw thin separator
                drawContext.fill(panelX + PADDING + 10, currentY - 2, panelX + panelWidth - PADDING - 10, currentY - 1, 0xFF333333);
            }
        }
    }

    private static int drawCallEntry(DrawContext drawContext, TextRenderer textRenderer, EmergencyCall call, int panelX, int y, int panelWidth, int index, boolean showCoords) {
        int x = panelX + PADDING;

        boolean isDeath = call.getType() == EmergencyCall.CallType.DEATH;
        boolean isRejected = call.isRejected();

        // Base label and color for the call type
        String typeLabel = isDeath ? "\u2620 Tod" : "\ud83d\udd14 Notruf";
        int typeColor = isDeath ? DEATH_COLOR : ECALL_COLOR;
        if (isRejected) {
            typeLabel = "\u2718 " + typeLabel;
            typeColor = REJECTED_COLOR;
        } else if (call.isAccepted()) {
            typeLabel = "\u2714 " + typeLabel;
            typeColor = ACCEPTED_COLOR;
        }

        // Build timer string and its color separately (death calls only)
        String timerStr = null;
        int timerColor = TIMER_OK_COLOR;
        if (isDeath && call.hasTimer()) {
            int remaining = call.getRemainingSeconds();
            timerStr = remaining > 0
                    ? String.format(" %d:%02d", remaining / 60, remaining % 60)
                    : " abgelaufen";
            if (remaining <= 0) {
                timerColor = TIMER_CRIT_COLOR;
            } else if (remaining < 30) {
                timerColor = (System.currentTimeMillis() / 500) % 2 == 0 ? TIMER_CRIT_COLOR : TIMER_CRIT_FLASH_COLOR;
            } else if (remaining < 60) {
                timerColor = TIMER_URGENT_COLOR;
            } else if (remaining < 180) {
                timerColor = TIMER_WARN_COLOR;
            } else {
                timerColor = TIMER_OK_COLOR;
            }
        }

        // Draw "#N \u2620 Tod" (or "\ud83d\udd14 Notruf") in its color, then the timer right after in its own color
        String callHeader = "#" + index + " " + typeLabel;
        drawContext.drawText(textRenderer, callHeader, x, y, typeColor, true);
        if (timerStr != null) {
            int headerWidth = textRenderer.getWidth(callHeader);
            drawContext.drawText(textRenderer, timerStr, x + headerWidth, y, timerColor, true);
        }
        y += LINE_HEIGHT;

        // Pending call: show waiting message instead of incomplete data
        if (call.isPending()) {
            drawContext.drawText(textRenderer, "\u23f3 Warten auf Daten\u00fcbertragung", x + 4, y, PENDING_COLOR, true);
            y += LINE_HEIGHT;
            return y;
        }

        // Use dimmed colors for rejected calls
        int infoColor = isRejected ? 0xFF666666 : INFO_COLOR;

        // Caller name
        String callerLine = "Anrufer: " + call.getCallerName();
        drawContext.drawText(textRenderer, callerLine, x + 4, y, infoColor, true);
        y += LINE_HEIGHT;

        // Reason
        String reasonLine = "Grund: " + call.getReason();
        // Truncate if too long
        if (textRenderer.getWidth(reasonLine) > panelWidth - PADDING * 2 - 4) {
            while (textRenderer.getWidth(reasonLine + "...") > panelWidth - PADDING * 2 - 4 && reasonLine.length() > 10) {
                reasonLine = reasonLine.substring(0, reasonLine.length() - 1);
            }
            reasonLine += "...";
        }
        drawContext.drawText(textRenderer, reasonLine, x + 4, y, infoColor, true);
        y += LINE_HEIGHT;

        // Location — alternates between coordinates and location name every 7 s
        String locName = call.getLocationName();
        String locLine;
        if (showCoords || locName == null || locName.isEmpty()) {
            locLine = "Ort: " + String.format("X:%.0f Y:%.0f Z:%.0f", call.getX(), call.getY(), call.getZ());
        } else {
            locLine = "Ort: " + locName;
        }
        drawContext.drawText(textRenderer, locLine, x + 4, y, infoColor, true);
        y += LINE_HEIGHT;

        // Rejected call: show "Zurückgewiesen von X"
        if (isRejected) {
            String rejectedLine = "\u2718 Zur\u00fcckgewiesen";
            if (call.getRejectedBy() != null && !call.getRejectedBy().isEmpty()) {
                rejectedLine += " von " + call.getRejectedBy();
            }
            drawContext.drawText(textRenderer, rejectedLine, x + 4, y, REJECTED_COLOR, true);
            y += LINE_HEIGHT;
        }

        // Assigned medic (if any, and not rejected)
        if (!isRejected && call.isAccepted()) {
            String medicLine = "Medic: " + call.getAssignedMedic();
            drawContext.drawText(textRenderer, medicLine, x + 4, y, MEDIC_COLOR, true);
            y += LINE_HEIGHT;
        }

        return y;
    }

    private static int calculateEntryHeight(EmergencyCall call) {
        if (call.isPending()) {
            return 2 * LINE_HEIGHT; // type line + waiting line
        }
        // type + caller + reason + location = 4 lines
        int lines = 4;
        if (call.isRejected()) lines++;  // "Zurückgewiesen von X" line
        else if (call.isAccepted()) lines++; // medic line
        return lines * LINE_HEIGHT;
    }
}

