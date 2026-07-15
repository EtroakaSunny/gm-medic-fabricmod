package de.dorikku.gmmedicmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.dorikku.gmmedicmod.command.DebugCommands;
import de.dorikku.gmmedicmod.command.HudCommands;
import de.dorikku.gmmedicmod.command.VehicleCommands;
import de.dorikku.gmmedicmod.config.VehicleConfig;
import de.dorikku.gmmedicmod.handler.ChatMessageHandler;
import de.dorikku.gmmedicmod.hud.EmergencyCallHud;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.network.ApiConfig;
import de.dorikku.gmmedicmod.network.ApiConnection;
import de.dorikku.gmmedicmod.render.CallTargetHighlightRenderer;
import de.dorikku.gmmedicmod.vehicle.VehicleAutomation;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.Locale;

public class GMMedicClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EmergencyCallManager.getInstance().setInDuty(false);
        EmergencyCallManager.getInstance().setEventListener(ApiConnection.getInstance());
        ApiConfig.getInstance(); // pre-load config on startup
        VehicleConfig.getInstance(); // pre-load vehicle automation config

        ClientTickEvents.START_CLIENT_TICK.register(VehicleAutomation::tick);

        ClientReceiveMessageEvents.GAME.register(ChatMessageHandler::onGameMessage);

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(GMMedic.MOD_ID, "emergency_calls_hud"),
                EmergencyCallHud::render
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
            ApiConnection.getInstance().disconnect();
            GMMedic.LOGGER.info("[GM-Medic] Disconnected — duty reset, API connection closed");
        });

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            ClientCommandRegistrationCallback.EVENT.register(DebugCommands::register);
        }

        ClientCommandRegistrationCallback.EVENT.register(GMMedicClient::registerStatusCommand);
        ClientCommandRegistrationCallback.EVENT.register(HudCommands::register);
        ClientCommandRegistrationCallback.EVENT.register(VehicleCommands::register);
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

    private static void registerApiCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(ClientCommands.literal("gmapi")
            .then(ClientCommands.literal("status").executes(ctx -> {
                ApiConnection conn = ApiConnection.getInstance();
                ApiConfig cfg = ApiConfig.getInstance();
                String url = cfg.isConfigured() ? cfg.getServerUrl() : "(nicht konfiguriert)";
                String state = !cfg.isConfigured() ? "deaktiviert"
                        : conn.isAuthenticated() ? "verbunden & authentifiziert"
                        : conn.isConnected() ? "verbunden (nicht auth.)"
                        : "getrennt";
                ctx.getSource().sendFeedback(Component.literal("[GM-Medic API] Status: " + state + " | URL: " + url));
                return 1;
            }))
            .then(ClientCommands.literal("token").executes(ctx -> {
                String token = ApiConfig.getInstance().getAuthToken();
                ctx.getSource().sendFeedback(Component.literal("[GM-Medic API] Token: " + token));
                return 1;
            }))
            .then(ClientCommands.literal("reset-token").executes(ctx -> {
                ApiConfig.getInstance().resetAuthToken();
                ctx.getSource().sendFeedback(
                    Component.literal("[GM-Medic API] Neues Token generiert. Neuverbindung erforderlich.").withStyle(ChatFormatting.YELLOW)
                );
                if (ApiConnection.getInstance().isConnected()) {
                    ApiConnection.getInstance().disconnect();
                    ApiConnection.getInstance().connect();
                }
                return 1;
            }))
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
        );
    }
}
