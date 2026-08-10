package de.dorikku.gmmedicmod.gui;

import de.dorikku.gmmedicmod.network.ApiConfig;
import de.dorikku.gmmedicmod.network.ApiConnection;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Replaces {@code /gmapi status}, {@code /gmapi token} and {@code /gmapi reset-token}.
 * {@code /gmapi url} deliberately stays a command (out of scope for this screen).
 */
public class ApiSettingsScreen extends AbstractGMMedicScreen {

    private Button resetButton;
    private boolean confirmingReset;

    public ApiSettingsScreen(Screen parent) {
        super(Component.literal("API-Verwaltung"), parent);
    }

    @Override
    protected void initWidgets() {
        confirmingReset = false;
        ApiConnection conn = ApiConnection.getInstance();
        ApiConfig cfg = ApiConfig.getInstance();
        int x = centeredX(BUTTON_WIDTH);
        int y = this.height / 6 + 24;

        String state = !cfg.isConfigured() ? "deaktiviert"
                : conn.isAuthenticated() ? "verbunden & authentifiziert"
                : conn.isConnected() ? "verbunden (nicht authentifiziert)"
                : "getrennt";
        this.addRenderableWidget(new StringWidget(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.literal("Status: " + state), this.font));
        y += ROW_SPACING;

        String url = cfg.isConfigured() ? cfg.getServerUrl() : "(nicht konfiguriert)";
        this.addRenderableWidget(new StringWidget(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.literal("URL: " + url), this.font));
        y += ROW_SPACING;

        this.addRenderableWidget(new StringWidget(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.literal("Ändern nur über /gmapi url <adresse>").withStyle(ChatFormatting.GRAY), this.font));
        y += ROW_SPACING + 6;

        this.addRenderableWidget(
                CycleButton.onOffBuilder(cfg.isUpdateNoticeEnabled())
                        .create(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Update-Hinweis"),
                                (btn, value) -> ApiConfig.getInstance().setUpdateNoticeEnabled(value))
        );
        y += ROW_SPACING + 6;

        EditBox tokenBox = new EditBox(this.font, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.literal("Token"));
        tokenBox.setValue(ApiConfig.getInstance().getAuthToken());
        tokenBox.setEditable(false);
        this.addRenderableWidget(tokenBox);
        y += ROW_SPACING + 6;

        resetButton = this.addRenderableWidget(
                Button.builder(Component.literal("Neues Token generieren"), b -> onResetPressed())
                        .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build()
        );
        y += ROW_SPACING + 10;

        addBackButton(y);
    }

    private void onResetPressed() {
        if (!confirmingReset) {
            confirmingReset = true;
            resetButton.setMessage(Component.literal("Sicher? Erneut klicken").withStyle(ChatFormatting.YELLOW));
            return;
        }
        ApiConfig.getInstance().resetAuthToken();
        if (ApiConnection.getInstance().isConnected()) {
            ApiConnection.getInstance().disconnect();
            ApiConnection.getInstance().connect();
        }
        this.minecraft.setScreen(new ApiSettingsScreen(this.parent));
    }
}
