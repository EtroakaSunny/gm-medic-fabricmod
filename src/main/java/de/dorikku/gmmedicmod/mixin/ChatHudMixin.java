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
 * Fallback intercept — catches messages when they are added to the chat HUD.
 */
@Mixin(ChatHud.class)
public class ChatHudMixin {

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("HEAD"))
    private void onAddMessage(Text message, @Nullable MessageSignatureData signature, @Nullable MessageIndicator indicator, CallbackInfo ci) {
        try {
            ChatMessageHandler.processFromMixin(message);
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic] Error in ChatHud intercept", e);
        }
    }
}
