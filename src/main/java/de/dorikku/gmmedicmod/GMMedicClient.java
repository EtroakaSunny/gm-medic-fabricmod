package de.dorikku.gmmedicmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.dorikku.gmmedicmod.blood.BloodDrawAssistant;
import de.dorikku.gmmedicmod.command.DebugCommands;
import de.dorikku.gmmedicmod.config.VehicleConfig;
import de.dorikku.gmmedicmod.gui.GMMedicMenuScreen;
import de.dorikku.gmmedicmod.handler.CallArrivalCountdown;
import de.dorikku.gmmedicmod.handler.ChatMessageHandler;
import de.dorikku.gmmedicmod.hud.AlarmHud;
import de.dorikku.gmmedicmod.hud.EmergencyCallHud;
import de.dorikku.gmmedicmod.manager.AlarmManager;
import de.dorikku.gmmedicmod.manager.BloodDonationManager;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import de.dorikku.gmmedicmod.network.ApiConfig;
import de.dorikku.gmmedicmod.network.ApiConnection;
import de.dorikku.gmmedicmod.network.UpdateNotifier;
import de.dorikku.gmmedicmod.render.BloodTargetHighlightRenderer;
import de.dorikku.gmmedicmod.render.CallTargetHighlightRenderer;
import de.dorikku.gmmedicmod.vehicle.VehicleAutomation;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public class GMMedicClient implements ClientModInitializer {

    private static final KeyBinding.Category KEY_CATEGORY =
            KeyBinding.Category.create(Identifier.of(GMMedic.MOD_ID, "main"));

    private static final KeyBinding OPEN_MENU_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.gm-medic.open_menu",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            KEY_CATEGORY
    ));

    @Override
    public void onInitializeClient() {
        EmergencyCallManager.getInstance().setInDuty(false);
        EmergencyCallManager.getInstance().setEventListener(ApiConnection.getInstance());
        ApiConfig.getInstance(); // pre-load config on startup
        VehicleConfig.getInstance(); // pre-load vehicle automation config

        ClientTickEvents.START_CLIENT_TICK.register(VehicleAutomation::tick);
        ClientTickEvents.START_CLIENT_TICK.register(BloodDrawAssistant::tick);
        ClientTickEvents.START_CLIENT_TICK.register(CallArrivalCountdown::tick);
        ClientTickEvents.END_CLIENT_TICK.register(UpdateNotifier::tick);

        // Keybind opens the settings menu directly; only fires outside of another open screen,
        // matching how most single-purpose mod-settings hotkeys behave.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_MENU_KEY.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new GMMedicMenuScreen(null));
                }
            }
        });

        ClientReceiveMessageEvents.GAME.register(ChatMessageHandler::onGameMessage);

        HudElementRegistry.addLast(
                Identifier.of(GMMedic.MOD_ID, "emergency_calls_hud"),
                EmergencyCallHud::render
        );

        HudElementRegistry.addLast(
                Identifier.of(GMMedic.MOD_ID, "alarm_hud"),
                AlarmHud::render
        );

        WorldRenderEvents.AFTER_ENTITIES.register(CallTargetHighlightRenderer::render);
        WorldRenderEvents.AFTER_ENTITIES.register(BloodTargetHighlightRenderer::render);

        // Connect to the API as soon as a GermanMiner server is joined (not just on duty),
        // so the client is online for the whole session.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerInfo server = client.getCurrentServerEntry();
            String address = server != null ? server.address : null;
            if (address != null && address.toLowerCase(Locale.ROOT).contains("germanminer.de")) {
                GMMedic.LOGGER.info("[GM-Medic] GermanMiner server joined ({}) — connecting to API", address);
                // Starts the delay window for the update notice, so it isn't buried in join spam.
                UpdateNotifier.onJoin();
                ApiConnection.getInstance().connect();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EmergencyCallManager.getInstance().setInDuty(false);
            AlarmManager.getInstance().clear();
            BloodDonationManager.getInstance().clear();
            UpdateNotifier.onDisconnect();
            ApiConnection.getInstance().disconnect();
            GMMedic.LOGGER.info("[GM-Medic] Disconnected — duty reset, API connection closed");
        });

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            ClientCommandRegistrationCallback.EVENT.register(DebugCommands::register);
        }

        ClientCommandRegistrationCallback.EVENT.register(GMMedicClient::registerStatusCommand);
        ClientCommandRegistrationCallback.EVENT.register(GMMedicClient::registerMenuCommand);
        ClientCommandRegistrationCallback.EVENT.register(GMMedicClient::registerApiCommands);
        ClientCommandRegistrationCallback.EVENT.register(GMMedicClient::registerVerbalCallCommand);

        GMMedic.LOGGER.info("[GM-Medic] Client initialized");
    }

    private static void registerStatusCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("gmstatus").executes(ctx -> {
            EmergencyCallManager mgr = EmergencyCallManager.getInstance();
            ctx.getSource().sendFeedback(Text.literal(
                    "GM Medic: duty=" + (mgr.isInDuty() ? "on" : "off") + ", calls=" + mgr.getActiveCalls().size()
            ));
            return 1;
        }));
    }

    /**
     * Runs entirely client-side (never reaches the server) — the target of the
     * "[Mündlicher Notruf entgegennehmen]" click hint in {@link ChatMessageHandler}.
     */
    private static void registerVerbalCallCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("gmverbal")
                .then(ClientCommandManager.argument("officer", StringArgumentType.word()).executes(ctx -> {
                    ChatMessageHandler.acceptVerbalCall(StringArgumentType.getString(ctx, "officer"));
                    return 1;
                })));
    }

    private static void registerMenuCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("gmmenu").executes(ctx -> {
            MinecraftClient.getInstance().setScreen(new GMMedicMenuScreen(null));
            return 1;
        }));
    }

    /**
     * Only {@code url} stays a command — every other API setting (status, token, reset-token)
     * moved into {@link GMMedicMenuScreen}'s API tab.
     */
    private static void registerApiCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(ClientCommandManager.literal("gmapi")
            .then(ClientCommandManager.literal("url")
                .then(ClientCommandManager.argument("serverUrl", StringArgumentType.greedyString()).executes(ctx -> {
                    String url = StringArgumentType.getString(ctx, "serverUrl");
                    ApiConfig.getInstance().setServerUrl(url);
                    if (ApiConfig.getInstance().isConfigured()) {
                        ctx.getSource().sendFeedback(
                            Text.literal("[GM-Medic API] Server-URL gesetzt: " + url).formatted(Formatting.GREEN)
                        );
                        if (ApiConnection.getInstance().isConnected()) {
                            ApiConnection.getInstance().disconnect();
                            ApiConnection.getInstance().connect();
                        }
                    } else {
                        ApiConnection.getInstance().disconnect();
                        ctx.getSource().sendFeedback(
                            Text.literal("[GM-Medic API] Verbindung deaktiviert (serverUrl=" + url + ")").formatted(Formatting.YELLOW)
                        );
                    }
                    return 1;
                })))
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Text.literal(
                        "§eNutze §f/gmapi url <adresse> §e— alle anderen API-Einstellungen findest du über §f/gmmenu§e."
                ));
                return 1;
            })
        );
    }
}
