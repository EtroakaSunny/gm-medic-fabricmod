package de.dorikku.gmmedicmod.hud;

import de.dorikku.gmmedicmod.config.HudConfig;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.network.ApiConnection;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.List;

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

    public static void render(DrawContext drawContext, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
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
        TextRenderer textRenderer = client.textRenderer;
        List<EmergencyCall> calls = mgr.getActiveCalls();

        mgr.timeoutExpiredPendingCalls();

        long now = System.currentTimeMillis();
        calls.stream()
                .filter(c -> c.hasTimer() && !c.isResolved() && c.getDeadlineMs() > 0L && now > c.getDeadlineMs() + 10_000L)
                .toList()
                .forEach(mgr::removeCallInstance);
        mgr.removeExpiredRejectedCalls(10_000L);
        mgr.removeExpiredResolvedCalls(5_000L);

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        // Default anchor stays exactly the previous fixed top-right formula; a dragged position
        // (set via HudPositionScreen) overrides both coordinates uniformly, regardless of which
        // branch below is currently drawing.
        boolean customPos = HudConfig.getInstance().isNotrufePositionCustomized();
        int panelX = customPos
                ? clamp((int) Math.round(HudConfig.getInstance().getNotrufePosX() * screenWidth), 0, screenWidth)
                : screenWidth - panelWidth - margin;
        int panelY = customPos
                ? clamp((int) Math.round(HudConfig.getInstance().getNotrufePosY() * screenHeight), 0, screenHeight)
                : margin;

        String header = compact
                ? "§c§l🚑 " + calls.size()
                : "§c§l🚑 Notrufe (" + calls.size() + ")";

        if (calls.isEmpty()) {
            if (compact) {
                String emptyText = "§c🚑§8 Keine Notrufe";
                int emptyWidth = textRenderer.getWidth(emptyText) + padding * 2 + 4;
                int emptyX = customPos ? panelX : screenWidth - emptyWidth - margin;
                drawContext.fill(emptyX, panelY, emptyX + emptyWidth, panelY + padding + lineHeight + padding, BG_COLOR);
                drawContext.drawText(textRenderer, emptyText, emptyX + padding, panelY + padding, NO_CALLS_COLOR, true);
            } else {
                int panelHeight = padding * 2 + lineHeight * 2;
                drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, BG_COLOR);
                int y = panelY + padding;
                drawContext.drawText(textRenderer, header, panelX + padding, y, HEADER_COLOR, true);
                y += lineHeight;
                drawContext.drawText(textRenderer, "Keine aktiven Notrufe", panelX + padding, y, NO_CALLS_COLOR, true);
            }
            return;
        }

        int totalHeight = padding + lineHeight + padding;
        for (int i = 0; i < calls.size(); i++) {
            totalHeight += calculateEntryHeight(calls.get(i), lineHeight, compact);
            if (i < calls.size() - 1) totalHeight += entrySpacing;
        }
        totalHeight += padding;

        drawContext.fill(panelX, panelY, panelX + panelWidth, panelY + totalHeight, BG_COLOR);

        int currentY = panelY + padding;
        drawContext.drawText(textRenderer, header, panelX + padding, currentY, HEADER_COLOR, true);
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

    private static int drawCallEntry(DrawContext ctx, TextRenderer text, EmergencyCall call, int panelX, int y, int panelWidth, int index, boolean showCoords, boolean compact, int padding, int lineHeight) {
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
                int timerWidth = text.getWidth(timerStr);
                int availableForCaller = maxHeaderWidth - text.getWidth(prefix + " ") - timerWidth;
                if (!call.isPending()) {
                    String callerTrunc = truncate(text, call.getCallerName(), availableForCaller);
                    prefix = prefix + " " + callerTrunc;
                }
                ctx.drawText(text, prefix, x, y, typeColor, true);
                int timerX = x + panelWidth - padding * 2 - timerWidth;
                ctx.drawText(text, timerStr.trim(), timerX, y, timerColor, true);
            } else {
                if (!call.isPending()) {
                    int availableForCaller = maxHeaderWidth - text.getWidth(prefix + " ");
                    String callerTrunc = truncate(text, call.getCallerName(), availableForCaller);
                    prefix = prefix + " " + callerTrunc;
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

        if (call.isPending()) {
            String pendingText = compact ? "⏳ ..." : "⏳ Warten auf Datenübertragung";
            ctx.drawText(text, pendingText, x + indent, y, PENDING_COLOR, true);
            return y + lineHeight;
        }

        int infoColor = isResolved ? RESOLVED_COLOR : (isRejected ? DIMMED_COLOR : INFO_COLOR);
        int maxWidth = panelWidth - padding * 2 - indent;

        if (compact) {
            if (isEntangled) {
                ctx.drawText(text, "⚠ Fehler", x + indent, y, ENTANGLED_COLOR, true);
                y += lineHeight;
            }
            String reasonLine = truncateSuffix(text, call.getReason(), maxWidth, "..");
            ctx.drawText(text, reasonLine, x + indent, y, infoColor, true);
            y += lineHeight;

            String locLine = call.hasKnownLocation()
                    ? String.format("%.0f %.0f %.0f", call.getX(), call.getY(), call.getZ())
                    : "Unbekannt";
            if (call.hasKnownLocation() && text.getWidth(locLine) > maxWidth) {
                locLine = String.format("%.0f %.0f", call.getX(), call.getZ());
            }
            ctx.drawText(text, locLine, x + indent, y, infoColor, true);

            if (isResolved) {
                String rl = call.getResolvedReason() != null ? call.getResolvedReason() : "✔";
                int rlX = x + panelWidth - padding * 2 - text.getWidth(rl);
                if (rlX > x + indent + text.getWidth(locLine) + 2) {
                    ctx.drawText(text, rl, rlX, y, RESOLVED_LABEL, true);
                }
            } else if (isRejected) {
                String rj = "✘";
                int rjX = x + panelWidth - padding * 2 - text.getWidth(rj);
                if (rjX > x + indent + text.getWidth(locLine) + 2) {
                    ctx.drawText(text, rj, rjX, y, REJECTED_COLOR, true);
                }
            } else if (call.isAccepted()) {
                String mc = call.getAssignedMedic();
                int mcMaxWidth = maxWidth - text.getWidth(locLine) - 4;
                if (mcMaxWidth > 10) {
                    if (text.getWidth(mc) > mcMaxWidth) mc = mc.substring(0, Math.min(mc.length(), 6)) + "..";
                    ctx.drawText(text, mc, x + panelWidth - padding * 2 - text.getWidth(mc), y, MEDIC_COLOR, true);
                }
            }
            y += lineHeight;

        } else {
            ctx.drawText(text, "Anrufer: " + call.getCallerName(), x + indent, y, infoColor, true);
            y += lineHeight;

            String reasonLine = "Grund: " + call.getReason();
            if (text.getWidth(reasonLine) > maxWidth) {
                reasonLine = truncateSuffix(text, reasonLine, maxWidth, "...");
            }
            ctx.drawText(text, reasonLine, x + indent, y, infoColor, true);
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
            ctx.drawText(text, locLine, x + indent, y, infoColor, true);
            y += lineHeight;

            if (isEntangled) {
                ctx.drawText(text, "⚠ Fehler: Daten verschränkt", x + indent, y, ENTANGLED_COLOR, true);
                y += lineHeight;
            }

            if (isResolved) {
                String resolvedLabel = call.getResolvedReason() != null ? call.getResolvedReason() : "Erledigt";
                ctx.drawText(text, "✔ " + resolvedLabel, x + indent, y, RESOLVED_LABEL, true);
                y += lineHeight;
            }

            if (!isResolved && isRejected) {
                String line = "✘ Zurückgewiesen";
                if (call.getRejectedBy() != null && !call.getRejectedBy().isEmpty()) {
                    line += " von " + call.getRejectedBy();
                }
                ctx.drawText(text, line, x + indent, y, REJECTED_COLOR, true);
                y += lineHeight;
            }

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

    private static String truncate(TextRenderer text, String str, int maxWidth) {
        if (text.getWidth(str) <= maxWidth) return str;
        while (text.getWidth(str + "..") > maxWidth && str.length() > 1) {
            str = str.substring(0, str.length() - 1);
        }
        return str + "..";
    }

    private static String truncateSuffix(TextRenderer text, String str, int maxWidth, String suffix) {
        if (text.getWidth(str) <= maxWidth) return str;
        while (text.getWidth(str + suffix) > maxWidth && str.length() > suffix.length()) {
            str = str.substring(0, str.length() - 1);
        }
        return str + suffix;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
