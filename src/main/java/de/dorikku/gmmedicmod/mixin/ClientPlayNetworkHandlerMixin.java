package de.dorikku.gmmedicmod.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Kept as an empty stub — message processing is handled by ClientReceiveMessageEvents.GAME
 * and the profileless mixin in MessageHandlerMixin.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
}
