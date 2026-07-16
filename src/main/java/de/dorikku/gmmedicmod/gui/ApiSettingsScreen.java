package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.network.ApiConfig;
import de.dorikku.gmmedicmod.network.ApiConnection;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Replaces {@code /gmapi status}, {@code /gmapi token} and {@code /gmapi reset-token}.
 * {@code /gmapi url} deliberately stays a command (out of scope for this screen).
 */
public class ApiSettingsScreen extends AbstractGMMedicScreen {

    private ButtonWidget resetButton;
    private boolean confirmingReset;

    public ApiSettingsScreen(Screen parent) {
        super(Text.literal("API-Verwaltung"), parent);
    }

    @Override
    protected void init() {
        confirmingReset = false;
        ApiConnection conn = ApiConnection.getInstance();
        ApiConfig cfg = ApiConfig.getInstance();
        int x = centeredX(BUTTON_WIDTH);
        int y = this.height / 6 + 24;

        String state = !cfg.isConfigured() ? "deaktiviert"
                : conn.isAuthenticated() ? "verbunden & authentifiziert"
                : conn.isConnected() ? "verbunden (nicht authentifiziert)"
                : "getrennt";
        this.addDrawableChild(new TextWidget(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Text.literal("Status: " + state), this.textRenderer));
        y += ROW_SPACING;

        String url = cfg.isConfigured() ? cfg.getServerUrl() : "(nicht konfiguriert)";
        this.addDrawableChild(new TextWidget(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Text.literal("URL: " + url), this.textRenderer));
        y += ROW_SPACING;

        this.addDrawableChild(new TextWidget(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Text.literal("Ändern nur über /gmapi url <adresse>").formatted(Formatting.GRAY), this.textRenderer));
        y += ROW_SPACING + 6;

        TextFieldWidget tokenBox = new TextFieldWidget(this.textRenderer, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Text.literal("Token"));
        tokenBox.setText(ApiConfig.getInstance().getAuthToken());
        tokenBox.setEditable(false);
        this.addDrawableChild(tokenBox);
        y += ROW_SPACING + 6;

        resetButton = this.addDrawableChild(
                ButtonWidget.builder(Text.literal("Neues Token generieren"), b -> onResetPressed())
                        .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING + 10;

        addBackButton(y);
    }

    private void onResetPressed() {
        if (!confirmingReset) {
            confirmingReset = true;
            resetButton.setMessage(Text.literal("Sicher? Erneut klicken").formatted(Formatting.YELLOW));
            return;
        }
        ApiConfig.getInstance().resetAuthToken();
        if (ApiConnection.getInstance().isConnected()) {
            ApiConnection.getInstance().disconnect();
            ApiConnection.getInstance().connect();
        }
        this.client.setScreen(new ApiSettingsScreen(this.parent));
    }
}
