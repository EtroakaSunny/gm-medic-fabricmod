package de.dorikku.gmmedicmod.hud;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.config.HudConfig;
import de.dorikku.gmmedicmod.gui.CallActionScreen;
import de.dorikku.gmmedicmod.handler.CallActions;
import de.dorikku.gmmedicmod.hud.EmergencyCallHud.EntryBounds;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import de.dorikku.gmmedicmod.network.ApiConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * Makes the Notrufe panel clickable while the chat is open — the only time the cursor is free
 * in game. A left click on an open call accepts it (the transmission's ANNEHMEN button); a right
 * click, or any click on a call that is no longer open (e.g. your own accepted one), opens
 * {@link CallActionScreen} with all of that call's buttons.
 */
public final class CallHudInteraction {

    private static final int HOVER_COLOR = 0xFFFFFFFF;

    private CallHudInteraction() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof ChatScreen)) return;
            ScreenEvents.afterExtract(screen).register(
                    (s, graphics, mouseX, mouseY, tickProgress) -> renderHover(graphics, mouseX, mouseY));
            ScreenMouseEvents.allowMouseClick(screen).register(
                    (s, event) -> !handleClick(s, event.x(), event.y(), event.button()));
        });
    }

    private static boolean isEnabled() {
        return HudConfig.getInstance().isClickableCallsEnabled()
                && ApiConnection.getInstance().isFeatureUnlocked()
                && EmergencyCallManager.getInstance().isInDuty();
    }

    private static EntryBounds entryAt(double x, double y) {
        if (!isEnabled()) return null;
        for (EntryBounds bounds : EmergencyCallHud.getEntryBounds()) {
            if (bounds.contains(x, y)) return bounds;
        }
        return null;
    }

    /** Returns true when the click was the panel's and must not reach the chat. */
    private static boolean handleClick(Screen chat, double x, double y, int button) {
        EntryBounds bounds = entryAt(x, y);
        if (bounds == null) return false;
        EmergencyCall call = bounds.call();
        Minecraft client = Minecraft.getInstance();

        if (button == 0 && EmergencyCallHud.isOpen(call)) {
            ClickEvent accept = CallActions.get(call).get(CallActions.ACCEPT);
            if (accept != null) {
                GMMedic.LOGGER.info("[GM-Medic] Accepting {} from the panel", call.getCallerName());
                client.setScreen(null);
                CallActionScreen.execute(accept, client, null);
                return true;
            }
        }
        if (button == 0 || button == 1) {
            client.setScreen(new CallActionScreen(call, null));
        }
        return true;
    }

    private static void renderHover(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        EntryBounds bounds = entryAt(mouseX, mouseY);
        if (bounds == null) return;
        graphics.outline(bounds.x1(), bounds.y1(), bounds.x2() - bounds.x1(), bounds.y2() - bounds.y1(), HOVER_COLOR);

        EmergencyCall call = bounds.call();
        boolean hasButtons = !CallActions.get(call).isEmpty();
        List<Component> lines = new ArrayList<>();
        if (!hasButtons) {
            lines.add(Component.literal("§7Keine Chat-Buttons für diese Meldung erhalten"));
        } else if (EmergencyCallHud.isOpen(call) && CallActions.get(call).containsKey(CallActions.ACCEPT)) {
            lines.add(Component.literal("§aLinksklick: Annehmen"));
            lines.add(Component.literal("§7Rechtsklick: Alle Aktionen"));
        } else if (CallActionScreen.isMine(call)) {
            lines.add(Component.literal("§bKlick: Freigeben / Zurückweisen …"));
        } else {
            lines.add(Component.literal("§7Klick: Aktionen"));
        }
        graphics.setTooltipForNextFrame(Minecraft.getInstance().font, lines, Optional.empty(), mouseX, mouseY);
    }
}
