package de.dorikku.gmmedicmod.hud;

import de.dorikku.gmmedicmod.config.HudConfig;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.List;

public class EmergencyCallHud {

    // Normal layout constants
    private static final int PADDING_NORMAL = 6;
    private static final int LINE_HEIGHT_NORMAL = 11;
    private static final int MARGIN_NORMAL = 10;
    private static final int ENTRY_SPACING_NORMAL = 4;
    private static final int PANEL_WIDTH_NORMAL = 200;

    // Compact layout constants
    private static final int PADDING_COMPACT = 2;
    private static final int LINE_HEIGHT_COMPACT = 9;
    private static final int MARGIN_COMPACT = 2;
    private static final int ENTRY_SPACING_COMPACT = 3;
    private static final int PANEL_WIDTH_COMPACT = 130;

    // Colors
    private static final int BG_COLOR        = 0x90000000; // Semi-transparent black
    private static final int HEADER_COLOR    = 0xFFFF5555; // Red
    private static final int ECALL_COLOR     = 0xFFFFAA00; // Orange
    private static final int DEATH_COLOR     = 0xFFFF5555; // Red
    private static final int ACCEPTED_COLOR  = 0xFF55FF55; // Green
    private static final int REJECTED_COLOR  = 0xFFFF5555; // Red
    private static final int INFO_COLOR      = 0xFFAAAAAA; // Gray
    private static final int DIMMED_COLOR    = 0xFF666666; // Dimmed gray (rejected)
    private static final int RESOLVED_COLOR  = 0xFF555555; // Dark gray (resolved / done)
    private static final int RESOLVED_LABEL  = 0xFF888888; // Lighter gray for the resolved reason text
    private static final int MEDIC_COLOR     = 0xFF55FFFF; // Cyan
    private static final int NO_CALLS_COLOR  = 0xFF888888; // Dark gray
    private static final int PENDING_COLOR   = 0xFFFFFF55; // Yellow
    private static final int ENTANGLED_COLOR = 0xFFFF5555; // Red (entangled/error indicator)

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

        boolean compact = HudConfig.getInstance().isCompactMode();
        int padding      = compact ? PADDING_COMPACT      : PADDING_NORMAL;
        int lineHeight   = compact ? LINE_HEIGHT_COMPACT   : LINE_HEIGHT_NORMAL;
        int margin       = compact ? MARGIN_COMPACT        : MARGIN_NORMAL;
        int entrySpacing = compact ? ENTRY_SPACING_COMPACT : ENTRY_SPACING_NORMAL;
        int panelWidth   = compact ? PANEL_WIDTH_COMPACT   : PANEL_WIDTH_NORMAL;

        EmergencyCallManager mgr = EmergencyCallManager.getInstance();
        TextRenderer textRenderer = client.textRenderer;
        List<EmergencyCall> calls = mgr.getActiveCalls();

        // Timeout any pending transmission that has been parsing for too long
        mgr.timeoutExpiredPendingCalls();

        // Auto-remove expired entries (by object identity to avoid mass-removing calls sharing a name)
        long now = System.currentTimeMillis();
        calls.stream()
                .filter(c -> c.hasTimer() && !c.isResolved() && c.getDeadlineMs() > 0 && now > c.getDeadlineMs() + 10_000L)
                .toList()
                .forEach(mgr::removeCallInstance);
        mgr.removeExpiredRejectedCalls(10_000L);
        mgr.removeExpiredResolvedCalls(5_000L);

        int screenWidth = client.getWindow().getScaledWidth();
        int panelX = screenWidth - panelWidth - margin;
        int panelY = margin;
        int currentY = panelY;

        String header = compact
                ? "\u00a7c\u00a7l\ud83d\ude91 " + calls.size()
                : "\u00a7c\u00a7l\ud83d\ude91 Notrufe (" + calls.size() + ")";

        if (calls.isEmpty()) {
            if (compact) {
                // Minimal one-liner in compact mode
                String emptyText = "\u00a7c\ud83d\ude91\u00a78 Keine Notrufe";
                int emptyWidth = textRenderer.getWidth(emptyText) + padding * 2 + 4;
                int emptyX = screenWidth - emptyWidth - margin;
                int emptyHeight = padding + lineHeight + padding;
                drawContext.fill(emptyX, panelY, emptyX + emptyWidth, panelY + emptyHeight, BG_COLOR);
                drawContext.drawText(textRenderer, emptyText, emptyX + padding, panelY + padding, NO_CALLS_COLOR, true);
            } else {
                int panelHeight = padding * 2 + lineHeight * 2;
                drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, BG_COLOR);
                currentY += padding;
                drawContext.drawText(textRenderer, header, panelX + padding, currentY, HEADER_COLOR, true);
                currentY += lineHeight;
                drawContext.drawText(textRenderer, "Keine aktiven Notrufe", panelX + padding, currentY, NO_CALLS_COLOR, true);
            }
            return;
        }

        // Calculate total height
        int totalHeight = padding + lineHeight + padding;
        for (int i = 0; i < calls.size(); i++) {
            totalHeight += calculateEntryHeight(calls.get(i), lineHeight, compact);
            if (i < calls.size() - 1) totalHeight += entrySpacing;
        }
        totalHeight += padding;

        drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + totalHeight, BG_COLOR);

        currentY += padding;
        drawContext.drawText(textRenderer, header, panelX + padding, currentY, HEADER_COLOR, true);
        currentY += lineHeight + padding;
        drawContext.fill(panelX + padding, currentY - 2, panelX + panelWidth - padding, currentY - 1, 0xFF555555);

        boolean showCoords = (System.currentTimeMillis() / LOCATION_TOGGLE_MS) % 2 == 0;

        for (int i = 0; i < calls.size(); i++) {
            currentY = drawCallEntry(drawContext, textRenderer, calls.get(i), panelX, currentY,
                    panelWidth, i + 1, showCoords, compact, padding, lineHeight);
            if (i < calls.size() - 1) {
                currentY += entrySpacing;
                int divInset = compact ? 4 : 10;
                drawContext.fill(panelX + padding + divInset, currentY - 2, panelX + panelWidth - padding - divInset, currentY - 1, 0xFF333333);
            }
        }
    }

    private static int drawCallEntry(DrawContext ctx, TextRenderer text, EmergencyCall call,
                                     int panelX, int y, int panelWidth, int index,
                                     boolean showCoords, boolean compact, int padding, int lineHeight) {
        int x = panelX + padding;
        int indent = compact ? 0 : 4; // no inner indent in compact mode
        boolean isDeath = call.getType() == EmergencyCall.CallType.DEATH;
        boolean isRejected = call.isRejected();
        boolean isResolved = call.isResolved();
        boolean isEntangled = call.isEntangled();

        // Type label & color
        String typeLabel = isDeath ? "\u2620 Tod" : "\ud83d\udd14 Notruf";
        int typeColor = isDeath ? DEATH_COLOR : ECALL_COLOR;
        if (isResolved) {
            typeLabel = compact ? "\u2714" : "\u2714 " + typeLabel;
            typeColor = RESOLVED_COLOR;
        } else if (isRejected) {
            typeLabel = compact ? "\u2718" : "\u2718 " + typeLabel;
            typeColor = REJECTED_COLOR;
        } else if (call.isAccepted()) {
            typeLabel = compact ? "\u2714" : "\u2714 " + typeLabel;
            typeColor = ACCEPTED_COLOR;
        }

        // Timer (death calls only — hidden when resolved)
        String timerStr = null;
        int timerColor = TIMER_OK;
        if (isDeath && call.hasTimer() && !isResolved) {
            int remaining = call.getRemainingSeconds();
            timerStr = remaining > 0 ? String.format(" %d:%02d", remaining / 60, remaining % 60) : " \u2717";
            timerColor = remaining <= 0 ? TIMER_CRIT
                    : remaining < 30 ? ((System.currentTimeMillis() / 500) % 2 == 0 ? TIMER_CRIT : TIMER_FLASH)
                    : remaining < 120 ? TIMER_URGENT
                    : remaining < 180 ? TIMER_WARN : TIMER_OK;
        }

        // Header line: in compact mode combine index + status icon + caller name
        if (compact) {
            String prefix = "#" + index + " " + typeLabel;
            int maxHeaderWidth = panelWidth - padding * 2;

            if (timerStr != null) {
                // Reserve space for timer, then fit caller name in what's left
                int timerWidth = text.getWidth(timerStr);
                int availableForCaller = maxHeaderWidth - text.getWidth(prefix + " ") - timerWidth;
                if (!call.isPending()) {
                    String callerName = call.getCallerName();
                    String callerTrunc = callerName;
                    if (text.getWidth(callerTrunc) > availableForCaller) {
                        while (text.getWidth(callerTrunc + "..") > availableForCaller && callerTrunc.length() > 1) {
                            callerTrunc = callerTrunc.substring(0, callerTrunc.length() - 1);
                        }
                        callerTrunc += "..";
                    }
                    prefix += " " + callerTrunc;
                }
                ctx.drawText(text, prefix, x, y, typeColor, true);
                // Draw timer right-aligned so it never clips off the edge
                int timerX = x + panelWidth - padding * 2 - timerWidth;
                ctx.drawText(text, timerStr.trim(), timerX, y, timerColor, true);
            } else {
                // No timer — fit caller name within panel
                if (!call.isPending()) {
                    String callerName = call.getCallerName();
                    String callerTrunc = callerName;
                    int availableForCaller = maxHeaderWidth - text.getWidth(prefix + " ");
                    if (text.getWidth(callerTrunc) > availableForCaller) {
                        while (text.getWidth(callerTrunc + "..") > availableForCaller && callerTrunc.length() > 1) {
                            callerTrunc = callerTrunc.substring(0, callerTrunc.length() - 1);
                        }
                        callerTrunc += "..";
                    }
                    prefix += " " + callerTrunc;
                }
                ctx.drawText(text, prefix, x, y, typeColor, true);
            }
        } else {
            String callHeader = "#" + index + " " + typeLabel;
            ctx.drawText(text, callHeader, x, y, typeColor, true);
            if (timerStr != null) {
                ctx.drawText(text, timerStr, x + text.getWidth(callHeader), y, timerColor, true);
            }
        }
        y += lineHeight;

        // Pending
        if (call.isPending()) {
            String pendingText = compact ? "\u23f3 ..." : "\u23f3 Warten auf Daten\u00fcbertragung";
            ctx.drawText(text, pendingText, x + indent, y, PENDING_COLOR, true);
            return y + lineHeight;
        }

        int infoColor = isResolved ? RESOLVED_COLOR : isRejected ? DIMMED_COLOR : INFO_COLOR;
        int maxWidth = panelWidth - padding * 2 - indent;

        if (compact) {
            // Entangled warning line (compact)
            if (isEntangled) {
                ctx.drawText(text, "⚠ Fehler", x + indent, y, ENTANGLED_COLOR, true);
                y += lineHeight;
            }

            // Single line: reason (truncated)
            String reasonLine = call.getReason();
            if (text.getWidth(reasonLine) > maxWidth) {
                while (text.getWidth(reasonLine + "..") > maxWidth && reasonLine.length() > 5) {
                    reasonLine = reasonLine.substring(0, reasonLine.length() - 1);
                }
                reasonLine += "..";
            }
            ctx.drawText(text, reasonLine, x + indent, y, infoColor, true);
            y += lineHeight;

            // Compact coords
            String locLine = call.hasKnownLocation()
                    ? String.format("%.0f %.0f %.0f", call.getX(), call.getY(), call.getZ())
                    : "Unbekannt";
            // Truncate coords if they're too wide on their own
            if (call.hasKnownLocation() && text.getWidth(locLine) > maxWidth) {
                locLine = String.format("%.0f %.0f", call.getX(), call.getZ());
            }
            ctx.drawText(text, locLine, x + indent, y, infoColor, true);

            // Medic / rejected / resolved on same line, right-aligned (ensure no overlap with coords)
            if (isResolved) {
                String rl = call.getResolvedReason() != null ? call.getResolvedReason() : "\u2714";
                int rlX = x + panelWidth - padding * 2 - text.getWidth(rl);
                if (rlX > x + indent + text.getWidth(locLine) + 2) {
                    ctx.drawText(text, rl, rlX, y, RESOLVED_LABEL, true);
                }
            } else if (isRejected) {
                String rj = "\u2718";
                int rjX = x + panelWidth - padding * 2 - text.getWidth(rj);
                if (rjX > x + indent + text.getWidth(locLine) + 2) {
                    ctx.drawText(text, rj, rjX, y, REJECTED_COLOR, true);
                }
            } else if (call.isAccepted()) {
                String mc = call.getAssignedMedic();
                int mcMaxWidth = maxWidth - text.getWidth(locLine) - 4;
                if (mcMaxWidth > 10) {
                    if (text.getWidth(mc) > mcMaxWidth) {
                        mc = mc.substring(0, Math.min(mc.length(), 6)) + "..";
                    }
                    ctx.drawText(text, mc, x + panelWidth - padding * 2 - text.getWidth(mc), y, MEDIC_COLOR, true);
                }
            }
            y += lineHeight;
        } else {
            // Normal mode: separate lines for each field

            // Caller
            ctx.drawText(text, "Anrufer: " + call.getCallerName(), x + indent, y, infoColor, true);
            y += lineHeight;

            // Reason (truncated if needed)
            String reasonLine = "Grund: " + call.getReason();
            if (text.getWidth(reasonLine) > maxWidth) {
                while (text.getWidth(reasonLine + "...") > maxWidth && reasonLine.length() > 10) {
                    reasonLine = reasonLine.substring(0, reasonLine.length() - 1);
                }
                reasonLine += "...";
            }
            ctx.drawText(text, reasonLine, x + indent, y, infoColor, true);
            y += lineHeight;

            // Location
            String locName = call.getLocationName();
            String locLine = !call.hasKnownLocation()
                    ? "Ort: Unbekannt"
                    : (showCoords || locName == null || locName.isEmpty())
                    ? "Ort: " + String.format("X:%.0f Y:%.0f Z:%.0f", call.getX(), call.getY(), call.getZ())
                    : "Ort: " + locName;
            ctx.drawText(text, locLine, x + indent, y, infoColor, true);
            y += lineHeight;

            // Entangled warning line (normal mode)
            if (isEntangled) {
                ctx.drawText(text, "\u26a0 Fehler: Daten verschr\u00e4nkt", x + indent, y, ENTANGLED_COLOR, true);
                y += lineHeight;
            }

            // Resolved label
            if (isResolved) {
                String line = "\u2714 " + (call.getResolvedReason() != null ? call.getResolvedReason() : "Erledigt");
                ctx.drawText(text, line, x + indent, y, RESOLVED_LABEL, true);
                y += lineHeight;
            }

            // Rejected label
            if (!isResolved && isRejected) {
                String line = "\u2718 Zur\u00fcckgewiesen";
                if (call.getRejectedBy() != null && !call.getRejectedBy().isEmpty()) {
                    line += " von " + call.getRejectedBy();
                }
                ctx.drawText(text, line, x + indent, y, REJECTED_COLOR, true);
                y += lineHeight;
            }

            // Assigned medic
            if (!isResolved && !isRejected && call.isAccepted()) {
                ctx.drawText(text, "Medic: " + call.getAssignedMedic(), x + indent, y, MEDIC_COLOR, true);
                y += lineHeight;
            }
        }

        return y;
    }

    private static int calculateEntryHeight(EmergencyCall call, int lineHeight, boolean compact) {
        if (call.isPending()) return 2 * lineHeight;
        if (compact) {
            // header (index+status+caller) + reason + coords = 3 lines (+1 if entangled)
            int lines = 3;
            if (call.isEntangled()) lines++;
            return lines * lineHeight;
        }
        int lines = 4; // type + caller + reason + location
        if (call.isEntangled()) lines++;              // entangled warning label
        if (call.isResolved()) lines++;               // resolved reason label
        else if (call.isRejected() || call.isAccepted()) lines++; // rejected or medic label
        return lines * lineHeight;
    }
}

