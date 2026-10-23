package de.dorikku.gmmedicmod.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import de.dorikku.gmmedicmod.hud.CallHudInteraction;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands mouse presses on the Notrufe panel to {@link CallHudInteraction} before anything else
 * sees them. Hooked at the very start of {@code onButton} rather than through Fabric's screen
 * mouse events: LabyMod takes over {@code onButton} part-way through and cancels it, so those
 * events never fire there. A press the panel used is cancelled so the chat doesn't get it too.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void gmmedic$clickCallPanel(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (action == InputConstants.PRESS && CallHudInteraction.onMousePress(button.button())) {
            ci.cancel();
        }
    }
}
