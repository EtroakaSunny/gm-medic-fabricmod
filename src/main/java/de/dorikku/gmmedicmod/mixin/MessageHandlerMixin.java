package de.dorikku.gmmedicmod.mixin;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.handler.ChatMessageHandler;
import net.minecraft.client.network.message.MessageHandler;
import net.minecraft.network.message.MessageType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into MessageHandler — the central message processing class.
 *
 * We intercept TWO methods:
 *   1. onGameMessage(Text, boolean)  — system/game messages (e.g. /say, server announcements)
 *   2. onProfilelessMessage(Text, MessageType.Parameters) — messages from the server without
 *      a player profile. BungeeCord/Velocity proxy servers (like GermanMiner) commonly route
 *      FUNK, ZENTRALE, and duty messages through this path.
 */
@Mixin(MessageHandler.class)
public class MessageHandlerMixin {

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void onGameMessage(Text message, boolean overlay, CallbackInfo ci) {
        if (overlay) return; // Skip action bar messages
        try {
            GMMedic.LOGGER.info("[GM-Medic][MessageHandler] game: {}", message.getString());
            ChatMessageHandler.processFromMixin(message);
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic][MessageHandler] Error processing game message", e);
        }
    }

    @Inject(method = "onProfilelessMessage", at = @At("HEAD"))
    private void onProfilelessMessage(Text message, MessageType.Parameters params, CallbackInfo ci) {
        try {
            GMMedic.LOGGER.info("[GM-Medic][MessageHandler] profileless: {}", message.getString());
            ChatMessageHandler.processFromMixin(message);
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic][MessageHandler] Error processing profileless message", e);
        }
    }
}
