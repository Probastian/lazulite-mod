package de.lazuli.mixin;

import de.lazuli.tweaks.SpriteAnimationRegistry;
import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.renderer.texture.SpriteContents;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T6 (Disable Animations): freezes a sprite's animation by
 * no-oping its per-tick frame advance when the tweak excludes that sprite's
 * id.
 *
 * <p><strong>Confirmed via {@code javap}, corrects the spec/plan's guessed
 * single-class model:</strong> 26.1/26.2 split animation into {@code
 * SpriteContents$AnimatedTexture} (immutable definition, no tick) and
 * {@code SpriteContents$AnimationState} (the actual per-frame {@code tick()}
 * -- confirmed identical shape on 26.1 and 26.2).
 */
@Mixin(SpriteContents.AnimationState.class)
abstract class AnimationStateDisableAnimationsMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void lazuli$maybeFreeze(CallbackInfo ci) {
        String id = SpriteAnimationRegistry.idOf(this);
        if (id != null && !TweakEngineHandoff.require().shouldAnimate(id)) {
            ci.cancel();
        }
    }
}
