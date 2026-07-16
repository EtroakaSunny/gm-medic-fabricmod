package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.config.ReviveReplyConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;

/** Lets you edit the message the revive auto-reply sends; {@code {player}} is a placeholder. */
public class ReviveReplyTextScreen extends AbstractGMMedicScreen {

    private TextFieldWidget messageBox;

    public ReviveReplyTextScreen(Screen parent) {
        super(Text.literal("Auto-Antwort Text"), parent);
    }

    @Override
    protected void init() {
        int x = centeredX(BUTTON_WIDTH);
        int y = this.height / 6 + 24;

        this.addDrawableChild(new TextWidget(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Text.literal("{player} wird durch den Namen ersetzt"), this.textRenderer));
        y += ROW_SPACING;

        messageBox = new TextFieldWidget(this.textRenderer, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Text.literal("Antworttext"));
        messageBox.setMaxLength(200);
        messageBox.setText(ReviveReplyConfig.getInstance().getMessage());
        this.addDrawableChild(messageBox);
        y += ROW_SPACING + 6;

        this.addDrawableChild(
                ButtonWidget.builder(Text.literal("Zurücksetzen"), b -> messageBox.setText(ReviveReplyConfig.DEFAULT_MESSAGE))
                        .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING + 10;

        addBackButton(y);
    }

    @Override
    protected void back() {
        ReviveReplyConfig.getInstance().setMessage(messageBox.getText());
        super.back();
    }
}
