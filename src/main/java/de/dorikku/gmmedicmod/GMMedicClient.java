package de.dorikku.gmmedicmod;
import de.dorikku.gmmedicmod.command.DebugCommands;
import de.dorikku.gmmedicmod.handler.ChatMessageHandler;
import de.dorikku.gmmedicmod.hud.EmergencyCallHud;
import de.dorikku.gmmedicmod.manager.EmergencyCallManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandRegistryAccess;
public class GMMedicClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        GMMedic.LOGGER.info("GM Medic client initializing...");
        EmergencyCallManager.getInstance().setInDuty(false);
        ClientReceiveMessageEvents.GAME.register(ChatMessageHandler::onGameMessage);
        ClientReceiveMessageEvents.CHAT.register(ChatMessageHandler::onChatMessage);
        HudElementRegistry.addLast(
                Identifier.of(GMMedic.MOD_ID, "emergency_calls_hud"),
                EmergencyCallHud::render
        );
        // On JOIN we do NOT reset duty - on GermanMiner, the server sends duty messages
        // very quickly after connecting, sometimes before the JOIN event fires.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            GMMedic.LOGGER.info("Joined server - ready for duty messages");
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EmergencyCallManager.getInstance().setInDuty(false);
            GMMedic.LOGGER.info("Disconnected from server - duty status reset");
        });
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            ClientCommandRegistrationCallback.EVENT.register(DebugCommands::register);
            GMMedic.LOGGER.info("Debug commands registered (development environment)");
        }
        ClientCommandRegistrationCallback.EVENT.register(GMMedicClient::registerStatusCommand);
        GMMedic.LOGGER.info("GM Medic client initialized!");
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
