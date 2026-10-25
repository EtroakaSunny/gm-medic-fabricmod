package de.dorikku.gmmedicmod.mixin;

import de.dorikku.gmmedicmod.diagnosis.MicroscopeOverlay;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands clicks in a container menu to {@link MicroscopeOverlay} before the menu handles them.
 * Hooked on the screen itself rather than through Fabric's screen mouse events: LabyMod takes
 * over {@code MouseHandler.onButton} and passes the click on to the screen on its own, so those
 * events never fire there. A click the panel used is cancelled so the menu doesn't get it too.
 */
@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void gmmedic$clickMicroscopePanel(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (MicroscopeOverlay.onMouseClicked((AbstractContainerScreen<?>) (Object) this, event.x(), event.y(), event.button())) {
            cir.setReturnValue(true);
        }
    }
}
