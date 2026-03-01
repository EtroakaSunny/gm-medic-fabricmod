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
 * Primary message intercept — catches game messages and profileless (proxy) messages.
 */
@Mixin(MessageHandler.class)
public class MessageHandlerMixin {

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void onGameMessage(Text message, boolean overlay, CallbackInfo ci) {
        if (overlay) return;
        try {
            ChatMessageHandler.processFromMixin(message);
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic] Error processing game message", e);
        }
    }

    @Inject(method = "onProfilelessMessage", at = @At("HEAD"))
    private void onProfilelessMessage(Text message, MessageType.Parameters params, CallbackInfo ci) {
        try {
            ChatMessageHandler.processFromMixin(message);
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic] Error processing profileless message", e);
        }
    }
}
