package de.dorikku.gmmedicmod.mixin;

import de.dorikku.gmmedicmod.GMMedic;
import de.dorikku.gmmedicmod.handler.ChatMessageHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into the network handler to intercept messages at the packet level.
 * This fires BEFORE any other mod or LabyMod can process or suppress the message.
 *
 * We intercept TWO packet types:
 *   1. GameMessageS2CPacket  — system/game messages
 *   2. ProfilelessChatMessageS2CPacket — chat messages without a player profile,
 *      commonly used by BungeeCord/Velocity proxy servers like GermanMiner
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onGameMessage", at = @At("HEAD"))
    private void onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
        try {
            Text content = packet.content();
            GMMedic.LOGGER.info("[GM-Medic][PacketMixin] game: {}", content.getString());
            ChatMessageHandler.processFromMixin(content);
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic][PacketMixin] Error intercepting GameMessage", e);
        }
    }

    @Inject(method = "onProfilelessChatMessage", at = @At("HEAD"))
    private void onProfilelessChatMessage(ProfilelessChatMessageS2CPacket packet, CallbackInfo ci) {
        try {
            Text content = packet.message();
            GMMedic.LOGGER.info("[GM-Medic][PacketMixin] profileless: {}", content.getString());
            ChatMessageHandler.processFromMixin(content);
        } catch (Exception e) {
            GMMedic.LOGGER.error("[GM-Medic][PacketMixin] Error intercepting ProfilelessChatMessage", e);
        }
    }
}
