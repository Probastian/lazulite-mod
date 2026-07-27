package de.lazuli.mixin;

import de.lazuli.tweaks.SpriteAnimationRegistry;
import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.texture.SpriteContents;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T6 (Disable Animations): freezes a sprite's animation by
 * no-oping its per-tick frame advance when the tweak excludes that sprite's
 * id (resolved via {@link SpriteAnimationRegistry}, since {@code
 * Animator} has no usable back-reference field of its own -- see
 * {@code docs/specs/tweaks-hooks-wiring-plan.md}'s Risks section).
 *
 * <p><strong>Confirmed via {@code javap}, corrects the spec/plan's guessed
 * {@code SpriteContents$Ticker}, which does not exist:</strong> the real
 * inner class is {@code SpriteContents$Animator} with a public {@code
 * tick()} method.
 */
@Mixin(SpriteContents.Animator.class)
abstract class SpriteContentsAnimatorDisableAnimationsMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void lazuli$maybeFreeze(CallbackInfo ci) {
        String id = SpriteAnimationRegistry.idOf(this);
        if (id != null && !TweakEngineHandoff.require().shouldAnimate(id)) {
            ci.cancel();
        }
    }
}
