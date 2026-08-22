package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.network.ClientPlayerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T14 (Freecam) Addendum 2 AD-6 -- real player keeps sliding/
 * gliding after Freecam activates mid-walk.
 *
 * <p><strong>Confirmed root cause via {@code javap -c} against this module's
 * own resolved merged Minecraft jar (Yarn port of {@code fabric-26.1}/{@code
 * fabric-26.2}'s {@code LocalPlayerFreecamZeroMovementInputMixin}):</strong>
 * {@code ClientPlayerEntity.tickMovementInput()} (public, overriding {@code
 * AbstractClientPlayerEntity.tickMovementInput()}) only refreshes {@code
 * sidewaysSpeed}/{@code forwardSpeed} from the live pressed-key state
 * ({@code Input.getMovementInput()}) inside the {@code isCamera()} branch --
 * the same {@code client.getCameraEntity() == this} gate row 112 already
 * documented for movement-key routing. Once Freecam activates and that gate
 * goes false, {@code ClientPlayerEntity.tickMovementInput()} instead falls
 * through to {@code super.tickMovementInput()} (bytecode: {@code
 * invokespecial AbstractClientPlayerEntity.tickMovementInput()}), whose body
 * decays {@code sidewaysSpeed}/{@code forwardSpeed} by a slow geometric
 * factor rather than a hard zero -- the player therefore keeps receiving a
 * shrinking-but-nonzero forward/strafe *input* signal every subsequent tick
 * for as long as Freecam stays active, which {@code travel()} keeps
 * consuming as real acceleration input -- the reported "sliding/gliding"
 * symptom. {@code upwardSpeed} is never written by {@code
 * ClientPlayerEntity.tickMovementInput()} at all (unused by player flight;
 * zeroed here anyway for symmetry/safety, per spec's explicit "all three
 * fields" target).
 *
 * <p>Fix: an unconditional {@code @Inject} at {@code HEAD} (fires every tick,
 * before vanilla's own internal {@code isCamera()} branch) zeroes all three
 * movement-accumulator fields whenever Freecam is active -- from {@code
 * AbstractClientPlayerEntity.tickMovementInput()}'s perspective this is
 * indistinguishable from the player having released every movement key, so
 * vanilla's own normal ground/air friction (elsewhere in {@code travel()})
 * takes over from there, exactly matching the spec's target behavior. Does
 * not reopen or change movement-key routing itself (row 112) -- WASD still
 * reaches only the freecam camera while active, never the real player.
 */
@Mixin(ClientPlayerEntity.class)
abstract class ClientPlayerEntityFreecamZeroMovementInputMixin {

    @Shadow
    public float sidewaysSpeed;

    @Shadow
    public float upwardSpeed;

    @Shadow
    public float forwardSpeed;

    @Inject(method = "tickMovementInput", at = @At("HEAD"))
    private void lazuli$zeroMovementInputDuringFreecam(CallbackInfo ci) {
        if (TweakEngineHandoff.require().isFreecamActive()) {
            this.sidewaysSpeed = 0.0f;
            this.upwardSpeed = 0.0f;
            this.forwardSpeed = 0.0f;
        }
    }
}
