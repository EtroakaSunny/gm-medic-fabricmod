package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.handler.CallActions;
import de.dorikku.gmmedicmod.handler.CallRejectPrompt;
import de.dorikku.gmmedicmod.model.EmergencyCall;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * Every chat button the server sent for one call, opened from the Notrufe panel (right-click, or a
 * click on a call that is no longer open). For a call the medic has accepted this is the
 * confirmation step before releasing (FREIGEBEN) or rejecting (ZURÜCKWEISEN) it. A rejection takes
 * its reason from the field here; left empty, the chat opens for it once the server asks.
 */
public class CallActionScreen extends AbstractGMMedicScreen {

    private final EmergencyCall call;
    private EditBox reasonBox;

    public CallActionScreen(EmergencyCall call, Screen parent) {
        super(Component.literal((call.getType() == EmergencyCall.CallType.DEATH ? "☠ Todesmeldung: " : "🔔 Notruf von ")
                + call.getCallerName()), parent);
        this.call = call;
    }

    /**
     * Replays a stored chat click event through the same vanilla handler a click in the chat uses,
     * so commands, dialogs and custom click actions behave exactly as there. {@code returnTo} is
     * the screen a confirmation prompt (if the game shows one) goes back to; null means the game.
     */
    public static void execute(ClickEvent event, Minecraft client, Screen returnTo) {
        if (client.player == null) return;
        defaultHandleGameClickEvent(event, client, returnTo);
    }

    /** True when the local player is the medic on this call. */
    public static boolean isMine(EmergencyCall call) {
        Minecraft client = Minecraft.getInstance();
        String medic = call.getAssignedMedic();
        return client.player != null && medic != null && medic.equalsIgnoreCase(client.player.getScoreboardName());
    }

    @Override
    protected void initWidgets() {
        int x = centeredX(BUTTON_WIDTH);
        int y = this.height / 6 + 24;
        Map<String, ClickEvent> buttons = CallActions.get(call);
        boolean mine = isMine(call);

        this.addRenderableWidget(new StringWidget(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.literal("§7" + statusLine(mine)), this.font));
        y += ROW_SPACING;

        List<String> labels = new ArrayList<>();
        for (String label : CallActions.ORDER) {
            if (buttons.containsKey(label)) labels.add(label);
        }
        for (String label : buttons.keySet()) {
            if (!labels.contains(label)) labels.add(label);
        }
        // Accepting again makes no sense for your own call; releasing or rejecting does.
        if (mine) labels.remove(CallActions.ACCEPT);

        if (labels.isEmpty()) {
            this.addRenderableWidget(new StringWidget(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                    Component.literal("§cKeine Chat-Buttons für diese Meldung erhalten."), this.font));
            y += ROW_SPACING;
        }

        for (String label : labels) {
            ClickEvent event = buttons.get(label);
            if (CallActions.REJECT.equals(label)) {
                reasonBox = new EditBox(this.font, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Grund"));
                reasonBox.setMaxLength(200);
                reasonBox.setHint(Component.literal("§8Grund (leer = im Chat eingeben)"));
                this.addRenderableWidget(reasonBox);
                y += ROW_SPACING;
            }
            this.addRenderableWidget(
                    Button.builder(Component.literal(displayName(label)), b -> run(label, event))
                            .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                            .build()
            );
            y += ROW_SPACING;
        }

        if (mine && !buttons.containsKey(CallActions.RELEASE) && call.getType() == EmergencyCall.CallType.DEATH) {
            this.addRenderableWidget(new StringWidget(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                    Component.literal("§8FREIGEBEN kam noch nicht im Chat."), this.font));
            y += ROW_SPACING;
        }

        y += 10;
        this.addRenderableWidget(
                Button.builder(Component.literal("Abbrechen"), b -> back())
                        .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
    }

    private void run(String label, ClickEvent event) {
        if (CallActions.REJECT.equals(label)) {
            CallRejectPrompt.arm(reasonBox != null ? reasonBox.getValue() : "");
        }
        Minecraft client = this.minecraft;
        client.setScreen(null);
        execute(event, client, null);
    }

    private String statusLine(boolean mine) {
        if (call.isResolved()) return "Erledigt" + (call.getResolvedReason() != null ? ": " + call.getResolvedReason() : "");
        if (call.isRejected()) return "Zurückgewiesen" + (call.getRejectedBy() != null ? " von " + call.getRejectedBy() : "");
        if (mine) return "Von dir angenommen — freigeben oder zurückweisen?";
        if (call.isAccepted()) return "Angenommen von " + call.getAssignedMedic();
        return "Offen — " + call.getReason();
    }

    private static String displayName(String label) {
        return switch (label) {
            case CallActions.ACCEPT -> "§a✔ Annehmen";
            case CallActions.RELEASE -> "§b↩ Freigeben";
            case CallActions.CALL -> "§e☎ Anrufen";
            case CallActions.REPORT -> "§c⚑ Melden";
            case CallActions.REJECT -> "§4✘ Zurückweisen";
            default -> label.charAt(0) + label.substring(1).toLowerCase(java.util.Locale.ROOT);
        };
    }
}
