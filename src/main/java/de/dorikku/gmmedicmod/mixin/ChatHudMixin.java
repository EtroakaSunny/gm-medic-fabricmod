package de.dorikku.gmmedicmod.mixin;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.handler.ChatMessageHandler;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into ChatHud to intercept messages when they're added to the chat display.
 * In MC 1.21.10, the public addMessage method has 3 parameters:
 *   addMessage(Text, MessageSignatureData, MessageIndicator)
 * This is a fallback — MessageHandlerMixin is the primary intercept point.
 */
@Mixin(ChatHud.class)
public class ChatHudMixin {

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("HEAD"))
    private void onAddMessage(Text message, @Nullable MessageSignatureData signature, @Nullable MessageIndicator indicator, CallbackInfo ci) {
        try {
            GMMedic.LOGGER.info("[GM-Medic][ChatHud] addMessage: {}", message.getString());
            ChatMessageHandler.processFromMixin(message);
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic][ChatHud] Error in addMessage intercept", e);
        }
    }
}
