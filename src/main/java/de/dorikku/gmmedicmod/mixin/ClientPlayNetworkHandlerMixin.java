package de.dorikku.gmmedicmod.mixin;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.handler.ChatMessageHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts messages at the packet level — fires before MessageHandler.
 * Acts as an early hook so messages are captured even if another mod suppresses them later.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        try {
            ChatMessageHandler.processFromMixin(packet.content());
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic] Error intercepting GameMessage", e);
        }
    }

    @Inject(method = "onProfilelessChatMessage", at = @At("HEAD"))
    private void onProfilelessChatMessage(ProfilelessChatMessageS2CPacket packet, CallbackInfo ci) {
        try {
            ChatMessageHandler.processFromMixin(packet.message());
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic] Error intercepting ProfilelessChatMessage", e);
        }
    }
}
