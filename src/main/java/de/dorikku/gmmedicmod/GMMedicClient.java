package de.dorikku.gmmedicmod;

import de.dorikku.gmmedicmod.api.ApiClient;
import de.dorikku.gmmedicmod.command.ApiCommands;
import de.dorikku.gmmedicmod.command.DebugCommands;
import de.dorikku.gmmedicmod.command.HudCommands;
import de.dorikku.gmmedicmod.config.ApiConfig;
import de.dorikku.gmmedicmod.handler.ChatMessageHandler;
import de.dorikku.gmmedicmod.hud.EmergencyCallHud;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import com.mojang.brigadier.CommandDispatcher;

public class GMMedicClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EmergencyCallManager.getInstance().setInDuty(false);

        // Message listeners
        ClientReceiveMessageEvents.GAME.register(ChatMessageHandler::onGameMessage);
        ClientReceiveMessageEvents.CHAT.register(ChatMessageHandler::onChatMessage);

        // HUD
        HudElementRegistry.addLast(
                Identifier.of(GMMedic.MOD_ID, "emergency_calls_hud"),
                EmergencyCallHud::render
        );

        // Connection events
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ApiClient.getInstance().disconnect();
            EmergencyCallManager.getInstance().setInDuty(false);
            GMMedic.LOGGER.info("[GM-Medic] Disconnected — duty reset, API disconnected");
        });

        // Initialize API config (loads from disk)
        ApiConfig.getInstance();

        // Debug commands (dev only)
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            ClientCommandRegistrationCallback.EVENT.register(DebugCommands::register);
        }

        // /gmstatus command (always available)
        ClientCommandRegistrationCallback.EVENT.register(GMMedicClient::registerStatusCommand);

        // /gmhud command (compact mode, status)
        ClientCommandRegistrationCallback.EVENT.register(HudCommands::register);

        // /gmapi command (API sync management — always available)
        ClientCommandRegistrationCallback.EVENT.register(ApiCommands::register);

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
}
