package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.config.ReviveReplyConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Lets you edit the message the revive auto-reply sends; {@code {player}} is a placeholder. */
public class ReviveReplyTextScreen extends AbstractGMMedicScreen {

    private EditBox messageBox;

    public ReviveReplyTextScreen(Screen parent) {
        super(Component.literal("Auto-Antwort Text"), parent);
    }

    @Override
    protected void init() {
        int x = centeredX(BUTTON_WIDTH);
        int y = this.height / 6 + 24;

        this.addRenderableWidget(new StringWidget(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.literal("{player} wird durch den Namen ersetzt"), this.font));
        y += ROW_SPACING;

        messageBox = new EditBox(this.font, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Antworttext"));
        messageBox.setMaxLength(200);
        messageBox.setValue(ReviveReplyConfig.getInstance().getMessage());
        this.addRenderableWidget(messageBox);
        y += ROW_SPACING + 6;

        this.addRenderableWidget(
                Button.builder(Component.literal("Zurücksetzen"), b -> messageBox.setValue(ReviveReplyConfig.DEFAULT_MESSAGE))
                        .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING + 10;

        addBackButton(y);
    }

    @Override
    protected void back() {
        ReviveReplyConfig.getInstance().setMessage(messageBox.getValue());
        super.back();
    }
}
