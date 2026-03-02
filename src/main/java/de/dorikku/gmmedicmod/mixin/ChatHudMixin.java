package de.dorikku.gmmedicmod.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Kept as an empty stub — message processing is handled by ClientReceiveMessageEvents.GAME
 * and the profileless mixin in MessageHandlerMixin.
 */
@Mixin(ChatHud.class)
public class ChatHudMixin {
}
