package de.dorikku.gmmedicmod.mixin;

import de.dorikku.gmmedicmod.vehicle.VehicleAutomation;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewrites the freshly built {@link PlayerInput} sneak flag for {@link VehicleAutomation}.
 * Done at the input layer (rather than via {@code KeyBinding.setPressed}) so the physically-held
 * key state is left intact, and at TAIL so it runs right before the {@code PlayerInputC2SPacket}
 * is sent.
 *
 * <ul>
 *   <li>{@link VehicleAutomation#shouldForceSneak()} — force the sneak flag on (e.g. to hold a
 *       seat) even when the key is not pressed.</li>
 *   <li>{@link VehicleAutomation#shouldSuppressSneak()} — strip the sneak flag so the server-side
 *       sneak dismount is held back until the siren can be switched off first.</li>
 * </ul>
 */
@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void gmmedic$suppressSneakDismount(CallbackInfo ci) {
        Input self = (Input) (Object) this;
        PlayerInput input = self.playerInput;
        if (VehicleAutomation.shouldForceSneak()) {
            if (!input.sneak()) {
                self.playerInput = new PlayerInput(
                        input.forward(), input.backward(), input.left(), input.right(),
                        input.jump(), true, input.sprint());
            }
        } else if (VehicleAutomation.shouldSuppressSneak() && input.sneak()) {
            self.playerInput = new PlayerInput(
                    input.forward(), input.backward(), input.left(), input.right(),
                    input.jump(), false, input.sprint());
        }
    }
}
