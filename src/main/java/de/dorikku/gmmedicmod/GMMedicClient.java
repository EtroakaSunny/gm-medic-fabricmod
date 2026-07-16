package de.dorikku.gmmedicmod;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.dorikku.gmmedicmod.command.DebugCommands;
import de.dorikku.gmmedicmod.config.VehicleConfig;
import de.dorikku.gmmedicmod.gui.GMMedicMenuScreen;
import de.dorikku.gmmedicmod.handler.ChatMessageHandler;
import de.dorikku.gmmedicmod.hud.AlarmHud;
import de.dorikku.gmmedicmod.hud.EmergencyCallHud;
import de.dorikku.gmmedicmod.manager.AlarmManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.network.ApiConfig;
import de.dorikku.gmmedicmod.network.ApiConnection;
import de.dorikku.gmmedicmod.render.CallTargetHighlightRenderer;
import de.dorikku.gmmedicmod.vehicle.VehicleAutomation;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.Locale;

public class GMMedicClient implements ClientModInitializer {

    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(GMMedic.MOD_ID, "main"));

    private static final KeyMapping OPEN_MENU_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.gm-medic.open_menu",
            InputConstants.UNKNOWN.getValue(),
            KEY_CATEGORY
    ));

    @Override
    public void onInitializeClient() {
        EmergencyCallManager.getInstance().setInDuty(false);
        EmergencyCallManager.getInstance().setEventListener(ApiConnection.getInstance());
        ApiConfig.getInstance(); // pre-load config on startup
        VehicleConfig.getInstance(); // pre-load vehicle automation config

        ClientTickEvents.START_CLIENT_TICK.register(VehicleAutomation::tick);

        // Keybind opens the settings menu directly; only fires outside of another open screen,
        // matching how most single-purpose mod-settings hotkeys behave.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_MENU_KEY.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(new GMMedicMenuScreen(null));
                }
            }
        });

        ClientReceiveMessageEvents.GAME.register(ChatMessageHandler::onGameMessage);

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(GMMedic.MOD_ID, "emergency_calls_hud"),
                EmergencyCallHud::render
        );

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(GMMedic.MOD_ID, "alarm_hud"),
                AlarmHud::render
        );

        LevelRenderEvents.AFTER_SOLID_FEATURES.register(CallTargetHighlightRenderer::render);

        // Connect to the API as soon as a GermanMiner server is joined (not just on duty),
        // so the client is online for the whole session.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerData server = client.getCurrentServer();
            String address = server != null ? server.ip : null;
            if (address != null && address.toLowerCase(Locale.ROOT).contains("germanminer.de")) {
                GMMedic.LOGGER.info("[GM-Medic] GermanMiner server joined ({}) — connecting to API", address);
                ApiConnection.getInstance().connect();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EmergencyCallManager.getInstance().setInDuty(false);
            AlarmManager.getInstance().clear();
            ApiConnection.getInstance().disconnect();
            GMMedic.LOGGER.info("[GM-Medic] Disconnected — duty reset, API connection closed");
        });

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            ClientCommandRegistrationCallback.EVENT.register(DebugCommands::register);
        }

        ClientCommandRegistrationCallback.EVENT.register(GMMedicClient::registerStatusCommand);
        ClientCommandRegistrationCallback.EVENT.register(GMMedicClient::registerMenuCommand);
        ClientCommandRegistrationCallback.EVENT.register(GMMedicClient::registerApiCommands);

        GMMedic.LOGGER.info("[GM-Medic] Client initialized");
    }

    private static void registerStatusCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(ClientCommands.literal("gmstatus").executes(ctx -> {
            EmergencyCallManager mgr = EmergencyCallManager.getInstance();
            ctx.getSource().sendFeedback(Component.literal(
                    "GM Medic: duty=" + (mgr.isInDuty() ? "on" : "off") + ", calls=" + mgr.getActiveCalls().size()
            ));
            return 1;
        }));
    }

    private static void registerMenuCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(ClientCommands.literal("gmmenu").executes(ctx -> {
            Minecraft.getInstance().gui.setScreen(new GMMedicMenuScreen(null));
            return 1;
        }));
    }

    /**
     * Only {@code url} stays a command — every other API setting (status, token, reset-token)
     * moved into {@link GMMedicMenuScreen}'s API tab.
     */
    private static void registerApiCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(ClientCommands.literal("gmapi")
            .then(ClientCommands.literal("url")
                .then(ClientCommands.argument("serverUrl", StringArgumentType.greedyString()).executes(ctx -> {
                    String url = StringArgumentType.getString(ctx, "serverUrl");
                    ApiConfig.getInstance().setServerUrl(url);
                    if (ApiConfig.getInstance().isConfigured()) {
                        ctx.getSource().sendFeedback(
                            Component.literal("[GM-Medic API] Server-URL gesetzt: " + url).withStyle(ChatFormatting.GREEN)
                        );
                        if (ApiConnection.getInstance().isConnected()) {
                            ApiConnection.getInstance().disconnect();
                            ApiConnection.getInstance().connect();
                        }
                    } else {
                        ApiConnection.getInstance().disconnect();
                        ctx.getSource().sendFeedback(
                            Component.literal("[GM-Medic API] Verbindung deaktiviert (serverUrl=" + url + ")").withStyle(ChatFormatting.YELLOW)
                        );
                    }
                    return 1;
                })))
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Component.literal(
                        "§eNutze §f/gmapi url <adresse> §e— alle anderen API-Einstellungen findest du über §f/gmmenu§e."
                ));
                return 1;
            })
        );
    }
}
