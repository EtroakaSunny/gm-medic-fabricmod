package de.dorikku.gmmedicmod.mixin;

import de.dorikku.gmmedicmod.vehicle.SirenMenu;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link SirenMenu} take the car's Auto-Menü without it ever appearing on screen. Both hooks
 * sit right after the handler's hop onto the client thread, so they run once, on that thread.
 */
@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "handleOpenScreen", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V"))
    private void gmmedic$takeAutoMenu(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (SirenMenu.interceptOpen(packet)) ci.cancel();
    }

    @Inject(method = "handleContainerClose", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V"))
    private void gmmedic$closeAutoMenu(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        // Vanilla would close whatever screen the player has open (e.g. the chat) here.
        if (SirenMenu.interceptClose(packet.getContainerId())) ci.cancel();
    }
}
