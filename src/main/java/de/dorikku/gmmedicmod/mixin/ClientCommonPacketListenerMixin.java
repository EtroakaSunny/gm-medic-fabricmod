package de.dorikku.gmmedicmod.mixin;

import de.dorikku.gmmedicmod.vehicle.SirenMenu;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link SirenMenu} about every swap-hands (F) packet that goes out, so it can tell the
 * player's own F press from its hidden one and let the player's Auto-Menü show as usual.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerMixin {

    @Inject(method = "send", at = @At("HEAD"))
    private void gmmedic$noticeSwapHands(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundPlayerActionPacket action
                && action.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
            SirenMenu.onSwapHandsSent();
        }
    }
}
