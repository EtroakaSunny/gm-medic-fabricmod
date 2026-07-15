package de.dorikku.gmmedicmod.hud;

import de.dorikku.gmmedicmod.config.HudConfig;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.network.ApiConnection;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public class EmergencyCallHud {

    private static final int PADDING_NORMAL      = 6;
    private static final int LINE_HEIGHT_NORMAL  = 11;
    private static final int MARGIN_NORMAL       = 10;
    private static final int ENTRY_SPACING_NORMAL = 4;
    private static final int PANEL_WIDTH_NORMAL  = 200;

    private static final int PADDING_COMPACT     = 2;
    private static final int LINE_HEIGHT_COMPACT = 9;
    private static final int MARGIN_COMPACT      = 2;
    private static final int ENTRY_SPACING_COMPACT = 3;
    private static final int PANEL_WIDTH_COMPACT = 130;

    private static final int BG_COLOR        = 0x90000000;
    private static final int HEADER_COLOR    = 0xFFFF5555;
    private static final int ECALL_COLOR     = 0xFFFFAA00;
    private static final int DEATH_COLOR     = 0xFFFF5555;
    private static final int ACCEPTED_COLOR  = 0xFF55FF55;
    private static final int REJECTED_COLOR  = 0xFFFF5555;
    private static final int INFO_COLOR      = 0xFFAAAAAA;
    private static final int DIMMED_COLOR    = 0xFF666666;
    private static final int RESOLVED_COLOR  = 0xFF555555;
    private static final int RESOLVED_LABEL  = 0xFF888888;
    private static final int MEDIC_COLOR     = 0xFF55FFFF;
    private static final int NO_CALLS_COLOR  = 0xFF888888;
    private static final int PENDING_COLOR   = 0xFFFFFF55;
    private static final int ENTANGLED_COLOR = 0xFFFF5555;

    private static final int TIMER_OK     = 0xFF55FF55;
    private static final int TIMER_WARN   = 0xFFFFAA00;
    private static final int TIMER_URGENT = 0xFFFFFF55;
    private static final int TIMER_CRIT   = 0xFFFFFFFF;
    private static final int TIMER_FLASH  = 0xFFFF5555;

    private static final long LOCATION_TOGGLE_MS = 7_000L;

    public static void render(GuiGraphicsExtractor drawContext, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        if (!ApiConnection.getInstance().isFeatureUnlocked()) return;
        if (!EmergencyCallManager.getInstance().isInDuty()) return;

        boolean compact = HudConfig.getInstance().isCompactMode();
        int padding      = compact ? PADDING_COMPACT      : PADDING_NORMAL;
        int lineHeight   = compact ? LINE_HEIGHT_COMPACT  : LINE_HEIGHT_NORMAL;
        int margin       = compact ? MARGIN_COMPACT       : MARGIN_NORMAL;
        int entrySpacing = compact ? ENTRY_SPACING_COMPACT : ENTRY_SPACING_NORMAL;
        int panelWidth   = compact ? PANEL_WIDTH_COMPACT  : PANEL_WIDTH_NORMAL;

        EmergencyCallManager mgr = EmergencyCallManager.getInstance();
        Font textRenderer = client.font;
        List<EmergencyCall> calls = mgr.getActiveCalls();

        mgr.timeoutExpiredPendingCalls();

        long now = System.currentTimeMillis();
        calls.stream()
                .filter(c -> c.hasTimer() && !c.isResolved() && c.getDeadlineMs() > 0L && now > c.getDeadlineMs() + 10_000L)
                .toList()
                .forEach(mgr::removeCallInstance);
        mgr.removeExpiredRejectedCalls(10_000L);
        mgr.removeExpiredResolvedCalls(5_000L);

        int screenWidth = client.getWindow().getGuiScaledWidth();
        int panelX = screenWidth - panelWidth - margin;

        String header = compact
                ? "§c§l🚑 " + calls.size()
                : "§c§l🚑 Notrufe (" + calls.size() + ")";

        if (calls.isEmpty()) {
            if (compact) {
                String emptyText = "§c🚑§8 Keine Notrufe";
                int emptyWidth = textRenderer.width(emptyText) + padding * 2 + 4;
                int emptyX = screenWidth - emptyWidth - margin;
                drawContext.fill(emptyX, margin, emptyX + emptyWidth, margin + padding + lineHeight + padding, BG_COLOR);
                drawContext.text(textRenderer, emptyText, emptyX + padding, margin + padding, NO_CALLS_COLOR, true);
            } else {
                int panelHeight = padding * 2 + lineHeight * 2;
                drawContext.fill(panelX, margin, panelX + panelWidth, margin + panelHeight, BG_COLOR);
                int y = margin + padding;
                drawContext.text(textRenderer, header, panelX + padding, y, HEADER_COLOR, true);
                y += lineHeight;
                drawContext.text(textRenderer, "Keine aktiven Notrufe", panelX + padding, y, NO_CALLS_COLOR, true);
            }
            return;
        }

        int totalHeight = padding + lineHeight + padding;
        for (int i = 0; i < calls.size(); i++) {
            totalHeight += calculateEntryHeight(calls.get(i), lineHeight, compact);
            if (i < calls.size() - 1) totalHeight += entrySpacing;
        }
        totalHeight += padding;

        drawContext.fill(panelX, margin, panelX + panelWidth, margin + totalHeight, BG_COLOR);

        int currentY = margin + padding;
        drawContext.text(textRenderer, header, panelX + padding, currentY, HEADER_COLOR, true);
        currentY += lineHeight + padding;
        drawContext.fill(panelX + padding, currentY - 2, panelX + panelWidth - padding, currentY - 1, RESOLVED_COLOR);

        boolean showCoords = System.currentTimeMillis() / LOCATION_TOGGLE_MS % 2 == 0;

        for (int i = 0; i < calls.size(); i++) {
            currentY = drawCallEntry(drawContext, textRenderer, calls.get(i), panelX, currentY, panelWidth, i + 1, showCoords, compact, padding, lineHeight);
            if (i < calls.size() - 1) {
                currentY += entrySpacing;
                int divInset = compact ? 4 : 10;
                drawContext.fill(panelX + padding + divInset, currentY - 2, panelX + panelWidth - padding - divInset, currentY - 1, 0xFF333333);
            }
        }
    }

    private static int drawCallEntry(GuiGraphicsExtractor ctx, Font text, EmergencyCall call, int panelX, int y, int panelWidth, int index, boolean showCoords, boolean compact, int padding, int lineHeight) {
        int x = panelX + padding;
        int indent = compact ? 0 : 4;
        boolean isDeath = call.getType() == EmergencyCall.CallType.DEATH;
        boolean isRejected = call.isRejected();
        boolean isResolved = call.isResolved();
        boolean isEntangled = call.isEntangled();

        String typeLabel = isDeath ? "☠ Tod" : "🔔 Notruf";
        int typeColor = isDeath ? DEATH_COLOR : ECALL_COLOR;
        if (isResolved) {
            typeLabel = compact ? "✔" : "✔ " + typeLabel;
            typeColor = RESOLVED_COLOR;
        } else if (isRejected) {
            typeLabel = compact ? "✘" : "✘ " + typeLabel;
            typeColor = REJECTED_COLOR;
        } else if (call.isAccepted()) {
            typeLabel = compact ? "✔" : "✔ " + typeLabel;
            typeColor = ACCEPTED_COLOR;
        }

        String timerStr = null;
        int timerColor = TIMER_OK;
        if (isDeath && call.hasTimer() && !isResolved) {
            int remaining = call.getRemainingSeconds();
            timerStr = remaining > 0 ? String.format(" %d:%02d", remaining / 60, remaining % 60) : " ✗";
            if (remaining <= 0) {
                timerColor = TIMER_CRIT;
            } else if (remaining < 30) {
                timerColor = System.currentTimeMillis() / 500L % 2 == 0 ? TIMER_CRIT : TIMER_FLASH;
            } else if (remaining < 60) {
                timerColor = TIMER_URGENT;
            } else if (remaining < 180) {
                timerColor = TIMER_WARN;
            } else {
                timerColor = TIMER_OK;
            }
        }

        if (compact) {
            String prefix = "#" + index + " " + typeLabel;
            int maxHeaderWidth = panelWidth - padding * 2;
            if (timerStr != null) {
                int timerWidth = text.width(timerStr);
                int availableForCaller = maxHeaderWidth - text.width(prefix + " ") - timerWidth;
                if (!call.isPending()) {
                    String callerTrunc = truncate(text, call.getCallerName(), availableForCaller);
                    prefix = prefix + " " + callerTrunc;
                }
                ctx.text(text, prefix, x, y, typeColor, true);
                int timerX = x + panelWidth - padding * 2 - timerWidth;
                ctx.text(text, timerStr.trim(), timerX, y, timerColor, true);
            } else {
                if (!call.isPending()) {
                    int availableForCaller = maxHeaderWidth - text.width(prefix + " ");
                    String callerTrunc = truncate(text, call.getCallerName(), availableForCaller);
                    prefix = prefix + " " + callerTrunc;
                }
                ctx.text(text, prefix, x, y, typeColor, true);
            }
        } else {
            String callHeader = "#" + index + " " + typeLabel;
            ctx.text(text, callHeader, x, y, typeColor, true);
            if (timerStr != null) {
                ctx.text(text, timerStr, x + text.width(callHeader), y, timerColor, true);
            }
        }

        y += lineHeight;

        if (call.isPending()) {
            String pendingText = compact ? "⏳ ..." : "⏳ Warten auf Datenübertragung";
            ctx.text(text, pendingText, x + indent, y, PENDING_COLOR, true);
            return y + lineHeight;
        }

        int infoColor = isResolved ? RESOLVED_COLOR : (isRejected ? DIMMED_COLOR : INFO_COLOR);
        int maxWidth = panelWidth - padding * 2 - indent;

        if (compact) {
            if (isEntangled) {
                ctx.text(text, "⚠ Fehler", x + indent, y, ENTANGLED_COLOR, true);
                y += lineHeight;
            }
            String reasonLine = truncateSuffix(text, call.getReason(), maxWidth, "..");
            ctx.text(text, reasonLine, x + indent, y, infoColor, true);
            y += lineHeight;

            String locLine = call.hasKnownLocation()
                    ? String.format("%.0f %.0f %.0f", call.getX(), call.getY(), call.getZ())
                    : "Unbekannt";
            if (call.hasKnownLocation() && text.width(locLine) > maxWidth) {
                locLine = String.format("%.0f %.0f", call.getX(), call.getZ());
            }
            ctx.text(text, locLine, x + indent, y, infoColor, true);

            if (isResolved) {
                String rl = call.getResolvedReason() != null ? call.getResolvedReason() : "✔";
                int rlX = x + panelWidth - padding * 2 - text.width(rl);
                if (rlX > x + indent + text.width(locLine) + 2) {
                    ctx.text(text, rl, rlX, y, RESOLVED_LABEL, true);
                }
            } else if (isRejected) {
                String rj = "✘";
                int rjX = x + panelWidth - padding * 2 - text.width(rj);
                if (rjX > x + indent + text.width(locLine) + 2) {
                    ctx.text(text, rj, rjX, y, REJECTED_COLOR, true);
                }
            } else if (call.isAccepted()) {
                String mc = call.getAssignedMedic();
                int mcMaxWidth = maxWidth - text.width(locLine) - 4;
                if (mcMaxWidth > 10) {
                    if (text.width(mc) > mcMaxWidth) mc = mc.substring(0, Math.min(mc.length(), 6)) + "..";
                    ctx.text(text, mc, x + panelWidth - padding * 2 - text.width(mc), y, MEDIC_COLOR, true);
                }
            }
            y += lineHeight;

        } else {
            ctx.text(text, "Anrufer: " + call.getCallerName(), x + indent, y, infoColor, true);
            y += lineHeight;

            String reasonLine = "Grund: " + call.getReason();
            if (text.width(reasonLine) > maxWidth) {
                reasonLine = truncateSuffix(text, reasonLine, maxWidth, "...");
            }
            ctx.text(text, reasonLine, x + indent, y, infoColor, true);
            y += lineHeight;

            String locName = call.getLocationName();
            String locLine;
            if (!call.hasKnownLocation()) {
                locLine = "Ort: Unbekannt";
            } else if (!showCoords && locName != null && !locName.isEmpty()) {
                locLine = "Ort: " + locName;
            } else {
                locLine = "Ort: " + String.format("X:%.0f Y:%.0f Z:%.0f", call.getX(), call.getY(), call.getZ());
            }
            ctx.text(text, locLine, x + indent, y, infoColor, true);
            y += lineHeight;

            if (isEntangled) {
                ctx.text(text, "⚠ Fehler: Daten verschränkt", x + indent, y, ENTANGLED_COLOR, true);
                y += lineHeight;
            }

            if (isResolved) {
                String resolvedLabel = call.getResolvedReason() != null ? call.getResolvedReason() : "Erledigt";
                ctx.text(text, "✔ " + resolvedLabel, x + indent, y, RESOLVED_LABEL, true);
                y += lineHeight;
            }

            if (!isResolved && isRejected) {
                String line = "✘ Zurückgewiesen";
                if (call.getRejectedBy() != null && !call.getRejectedBy().isEmpty()) {
                    line += " von " + call.getRejectedBy();
                }
                ctx.text(text, line, x + indent, y, REJECTED_COLOR, true);
                y += lineHeight;
            }

            if (!isResolved && !isRejected && call.isAccepted()) {
                ctx.text(text, "Medic: " + call.getAssignedMedic(), x + indent, y, MEDIC_COLOR, true);
                y += lineHeight;
            }
        }

        return y;
    }

    private static int calculateEntryHeight(EmergencyCall call, int lineHeight, boolean compact) {
        if (call.isPending()) return 2 * lineHeight;
        if (compact) {
            int lines = 3;
            if (call.isEntangled()) lines++;
            return lines * lineHeight;
        } else {
            int lines = 4;
            if (call.isEntangled()) lines++;
            if (call.isResolved()) lines++;
            else if (call.isRejected() || call.isAccepted()) lines++;
            return lines * lineHeight;
        }
    }

    private static String truncate(Font text, String str, int maxWidth) {
        if (text.width(str) <= maxWidth) return str;
        while (text.width(str + "..") > maxWidth && str.length() > 1) {
            str = str.substring(0, str.length() - 1);
        }
        return str + "..";
    }

    private static String truncateSuffix(Font text, String str, int maxWidth, String suffix) {
        if (text.width(str) <= maxWidth) return str;
        while (text.width(str + suffix) > maxWidth && str.length() > suffix.length()) {
            str = str.substring(0, str.length() - 1);
        }
        return str + suffix;
    }
}
