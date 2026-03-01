package de.dorikku.gmmedicmod.hud;

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

    // Colors
    private static final int BG_COLOR        = 0x90000000; // Semi-transparent black
    private static final int HEADER_COLOR    = 0xFFFF5555; // Red
    private static final int ECALL_COLOR     = 0xFFFFAA00; // Orange
    private static final int DEATH_COLOR     = 0xFFFF5555; // Red
    private static final int ACCEPTED_COLOR  = 0xFF55FF55; // Green
    private static final int REJECTED_COLOR  = 0xFFFF5555; // Red
    private static final int INFO_COLOR      = 0xFFAAAAAA; // Gray
    private static final int DIMMED_COLOR    = 0xFF666666; // Dimmed gray (rejected)
    private static final int MEDIC_COLOR     = 0xFF55FFFF; // Cyan
    private static final int NO_CALLS_COLOR  = 0xFF888888; // Dark gray
    private static final int PENDING_COLOR   = 0xFFFFFF55; // Yellow

    // Death timer colors
    private static final int TIMER_OK     = 0xFF55FF55; // > 3 min
    private static final int TIMER_WARN   = 0xFFFFAA00; // 1–3 min
    private static final int TIMER_URGENT = 0xFFFFFF55; // 30–60 s
    private static final int TIMER_CRIT   = 0xFFFFFFFF; // < 30 s (white)
    private static final int TIMER_FLASH  = 0xFFFF5555; // < 30 s (red flash)

    private static final long LOCATION_TOGGLE_MS = 7_000;

    public static void render(DrawContext drawContext, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        if (!EmergencyCallManager.getInstance().isInDuty()) return;

        EmergencyCallManager mgr = EmergencyCallManager.getInstance();
        TextRenderer textRenderer = client.textRenderer;
        List<EmergencyCall> calls = mgr.getActiveCalls();

        // Auto-remove expired entries
        long now = System.currentTimeMillis();
        calls.stream()
                .filter(c -> c.hasTimer() && c.getDeadlineMs() > 0 && now > c.getDeadlineMs() + 10_000L)
                .toList()
                .forEach(c -> mgr.removeCallByCallerName(c.getCallerName()));
        mgr.removeExpiredRejectedCalls(10_000L);

        int screenWidth = client.getWindow().getScaledWidth();
        int panelWidth = 200;
        int panelX = screenWidth - panelWidth - MARGIN;
        int panelY = MARGIN;
        int currentY = panelY;

        String header = "\u00a7c\u00a7l\ud83d\ude91 Notrufe (" + calls.size() + ")";

        if (calls.isEmpty()) {
            int panelHeight = PADDING * 2 + LINE_HEIGHT * 2;
            drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, BG_COLOR);
            currentY += PADDING;
            drawContext.drawText(textRenderer, header, panelX + PADDING, currentY, HEADER_COLOR, true);
            currentY += LINE_HEIGHT;
            drawContext.drawText(textRenderer, "Keine aktiven Notrufe", panelX + PADDING, currentY, NO_CALLS_COLOR, true);
            return;
        }

        // Calculate total height
        int totalHeight = PADDING + LINE_HEIGHT + PADDING;
        for (int i = 0; i < calls.size(); i++) {
            totalHeight += calculateEntryHeight(calls.get(i));
            if (i < calls.size() - 1) totalHeight += ENTRY_SPACING;
        }
        totalHeight += PADDING;

        drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + totalHeight, BG_COLOR);

        currentY += PADDING;
        drawContext.drawText(textRenderer, header, panelX + PADDING, currentY, HEADER_COLOR, true);
        currentY += LINE_HEIGHT + PADDING;
        drawContext.fill(panelX + PADDING, currentY - 2, panelX + panelWidth - PADDING, currentY - 1, 0xFF555555);

        boolean showCoords = (System.currentTimeMillis() / LOCATION_TOGGLE_MS) % 2 == 0;

        for (int i = 0; i < calls.size(); i++) {
            currentY = drawCallEntry(drawContext, textRenderer, calls.get(i), panelX, currentY, panelWidth, i + 1, showCoords);
            if (i < calls.size() - 1) {
                currentY += ENTRY_SPACING;
                drawContext.fill(panelX + PADDING + 10, currentY - 2, panelX + panelWidth - PADDING - 10, currentY - 1, 0xFF333333);
            }
        }
    }

    private static int drawCallEntry(DrawContext ctx, TextRenderer text, EmergencyCall call,
                                     int panelX, int y, int panelWidth, int index, boolean showCoords) {
        int x = panelX + PADDING;
        boolean isDeath = call.getType() == EmergencyCall.CallType.DEATH;
        boolean isRejected = call.isRejected();

        // Type label & color
        String typeLabel = isDeath ? "\u2620 Tod" : "\ud83d\udd14 Notruf";
        int typeColor = isDeath ? DEATH_COLOR : ECALL_COLOR;
        if (isRejected) {
            typeLabel = "\u2718 " + typeLabel;
            typeColor = REJECTED_COLOR;
        } else if (call.isAccepted()) {
            typeLabel = "\u2714 " + typeLabel;
            typeColor = ACCEPTED_COLOR;
        }

        // Timer (death calls only)
        String timerStr = null;
        int timerColor = TIMER_OK;
        if (isDeath && call.hasTimer()) {
            int remaining = call.getRemainingSeconds();
            timerStr = remaining > 0 ? String.format(" %d:%02d", remaining / 60, remaining % 60) : " abgelaufen";
            timerColor = remaining <= 0 ? TIMER_CRIT
                    : remaining < 30 ? ((System.currentTimeMillis() / 500) % 2 == 0 ? TIMER_CRIT : TIMER_FLASH)
                    : remaining < 60 ? TIMER_URGENT
                    : remaining < 180 ? TIMER_WARN : TIMER_OK;
        }

        // Header line
        String callHeader = "#" + index + " " + typeLabel;
        ctx.drawText(text, callHeader, x, y, typeColor, true);
        if (timerStr != null) {
            ctx.drawText(text, timerStr, x + text.getWidth(callHeader), y, timerColor, true);
        }
        y += LINE_HEIGHT;

        // Pending
        if (call.isPending()) {
            ctx.drawText(text, "\u23f3 Warten auf Daten\u00fcbertragung", x + 4, y, PENDING_COLOR, true);
            return y + LINE_HEIGHT;
        }

        int infoColor = isRejected ? DIMMED_COLOR : INFO_COLOR;

        // Caller
        ctx.drawText(text, "Anrufer: " + call.getCallerName(), x + 4, y, infoColor, true);
        y += LINE_HEIGHT;

        // Reason (truncated if needed)
        String reasonLine = "Grund: " + call.getReason();
        int maxWidth = panelWidth - PADDING * 2 - 4;
        if (text.getWidth(reasonLine) > maxWidth) {
            while (text.getWidth(reasonLine + "...") > maxWidth && reasonLine.length() > 10) {
                reasonLine = reasonLine.substring(0, reasonLine.length() - 1);
            }
            reasonLine += "...";
        }
        ctx.drawText(text, reasonLine, x + 4, y, infoColor, true);
        y += LINE_HEIGHT;

        // Location
        String locName = call.getLocationName();
        String locLine = (showCoords || locName == null || locName.isEmpty())
                ? "Ort: " + String.format("X:%.0f Y:%.0f Z:%.0f", call.getX(), call.getY(), call.getZ())
                : "Ort: " + locName;
        ctx.drawText(text, locLine, x + 4, y, infoColor, true);
        y += LINE_HEIGHT;

        // Rejected label
        if (isRejected) {
            String line = "\u2718 Zur\u00fcckgewiesen";
            if (call.getRejectedBy() != null && !call.getRejectedBy().isEmpty()) {
                line += " von " + call.getRejectedBy();
            }
            ctx.drawText(text, line, x + 4, y, REJECTED_COLOR, true);
            y += LINE_HEIGHT;
        }

        // Assigned medic
        if (!isRejected && call.isAccepted()) {
            ctx.drawText(text, "Medic: " + call.getAssignedMedic(), x + 4, y, MEDIC_COLOR, true);
            y += LINE_HEIGHT;
        }

        return y;
    }

    private static int calculateEntryHeight(EmergencyCall call) {
        if (call.isPending()) return 2 * LINE_HEIGHT;
        int lines = 4; // type + caller + reason + location
        if (call.isRejected() || call.isAccepted()) lines++;
        return lines * LINE_HEIGHT;
    }
}

