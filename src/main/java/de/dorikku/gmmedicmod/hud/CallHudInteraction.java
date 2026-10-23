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
 *
 * <p>Fed straight from the mouse handler ({@code MouseHandlerMixin}) and drawn by the panel
 * itself, not through Fabric's screen events: LabyMod routes mouse input around those, so with
 * it installed they never fire for the chat.</p>
 */
public final class CallHudInteraction {

    private static final int HOVER_COLOR = 0xFFFFFFFF;

    private CallHudInteraction() {}

    /**
     * The open chat screen, or null when the chat is closed (no cursor to click with). Asked via
     * {@code ChatComponent.isChatFocused()} besides vanilla's {@link ChatScreen}: LabyMod shows
     * its own chat input inside a wrapper screen ({@code LabyScreenRenderer}) and reports it as
     * focused through exactly that method.
     */
    private static Screen openChat(Minecraft client) {
        Screen screen = client.gui.screen();
        if (screen == null) return null;
        if (screen instanceof ChatScreen || client.gui.hud.getChat().isChatFocused()) return screen;
        return null;
    }

    /** A mouse press anywhere in the game; returns true when the panel used it. */
    public static boolean onMousePress(int button) {
        Minecraft client = Minecraft.getInstance();
        Screen chat = openChat(client);
        if (chat == null) return false;
        double x = client.mouseHandler.getScaledXPos(client.getWindow());
        double y = client.mouseHandler.getScaledYPos(client.getWindow());
        return handleClick(chat, x, y, button);
    }

    /** Called by the panel after drawing: outlines the call under the cursor while the chat is open. */
    public static void renderHover(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (openChat(client) == null) return;
        int x = (int) client.mouseHandler.getScaledXPos(client.getWindow());
        int y = (int) client.mouseHandler.getScaledYPos(client.getWindow());
        renderHover(graphics, x, y);
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
                client.gui.setScreen(null);
                CallActionScreen.execute(accept, client, null);
                return true;
            }
        }
        if (button == 0 || button == 1) {
            client.gui.setScreen(new CallActionScreen(call, null));
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
