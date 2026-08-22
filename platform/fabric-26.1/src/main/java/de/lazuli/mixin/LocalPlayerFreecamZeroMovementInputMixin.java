package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.player.LocalPlayer;

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
 * own resolved merged Minecraft jar:</strong> {@code LocalPlayer.applyInput()}
 * (public, overriding {@code LivingEntity.applyInput()}) only refreshes
 * {@code xxa}/{@code zza} from the live pressed-key state ({@code
 * ClientInput.getMoveVector()}) inside the {@code isControlledCamera()}
 * branch -- the same {@code minecraft.getCameraEntity() == this} gate row 112
 * already documented for movement-key routing. Once Freecam activates and
 * that gate goes false, {@code LocalPlayer.applyInput()} instead falls
 * through to {@code super.applyInput()} (bytecode: {@code invokespecial
 * LivingEntity.applyInput()}), whose entire body is {@code xxa *= 0.98f;
 * zza *= 0.98f;} -- a slow geometric decay of whatever {@code xxa}/{@code
 * zza} held on the exact tick Freecam activated, not a hard zero and not a
 * refresh from (now-absent) input. The player therefore keeps receiving a
 * shrinking-but-nonzero forward/strafe *input* signal every subsequent tick
 * for as long as Freecam stays active, which {@code travel()} keeps
 * consuming as real acceleration input -- the reported "sliding/gliding"
 * symptom. {@code yya} is never written by {@code LocalPlayer.applyInput()}
 * at all (unused by player flight; zeroed here anyway for symmetry/safety,
 * per spec's explicit "all three fields" target).
 *
 * <p>Fix: an unconditional {@code @Inject} at {@code HEAD} (fires every tick,
 * before vanilla's own internal {@code isControlledCamera()} branch) zeroes
 * all three movement-accumulator fields whenever Freecam is active -- from
 * {@code LivingEntity.applyInput()}'s perspective this is indistinguishable
 * from the player having released every movement key, so vanilla's own
 * normal ground/air friction (elsewhere in {@code travel()}) takes over from
 * there, exactly matching the spec's target behavior. Does not reopen or
 * change movement-key routing itself (row 112) -- WASD still reaches only
 * the freecam camera while active, never the real player.
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerFreecamZeroMovementInputMixin {

    @Shadow
    public float xxa;

    @Shadow
    public float yya;

    @Shadow
    public float zza;

    @Inject(method = "applyInput", at = @At("HEAD"))
    private void lazuli$zeroMovementInputDuringFreecam(CallbackInfo ci) {
        if (TweakEngineHandoff.require().isFreecamActive()) {
            this.xxa = 0.0f;
            this.yya = 0.0f;
            this.zza = 0.0f;
        }
    }
}
