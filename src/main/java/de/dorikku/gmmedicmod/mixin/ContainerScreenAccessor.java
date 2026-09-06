package de.dorikku.gmmedicmod.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes where a container menu actually sits on screen, so
 * {@link de.dorikku.gmmedicmod.diagnosis.MicroscopeOverlay} can park its checklist next to the
 * menu instead of guessing. Vanilla keeps all four values {@code protected}.
 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {

    @Accessor("leftPos")
    int gmmedic$getLeftPos();

    @Accessor("topPos")
    int gmmedic$getTopPos();

    @Accessor("imageWidth")
    int gmmedic$getImageWidth();

    @Accessor("imageHeight")
    int gmmedic$getImageHeight();
}
