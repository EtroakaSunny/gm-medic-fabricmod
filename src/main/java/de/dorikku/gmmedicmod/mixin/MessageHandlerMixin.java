package de.dorikku.gmmedicmod.mixin;

import net.minecraft.client.network.message.MessageHandler;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Kept as an empty stub — all messages (including profileless) are caught by
 * ClientReceiveMessageEvents.GAME since profileless messages internally route
 * through onGameMessage.
 */
@Mixin(MessageHandler.class)
public class MessageHandlerMixin {
}
