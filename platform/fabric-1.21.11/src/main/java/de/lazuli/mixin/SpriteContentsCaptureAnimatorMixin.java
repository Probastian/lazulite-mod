package de.lazuli.mixin;

import de.lazuli.tweaks.SpriteAnimationRegistry;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

import net.minecraft.client.texture.SpriteContents;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweaks spec T6 (Disable Animations): stashes the owning sprite id for
 * each created {@code Animator}, since {@code Animator} itself has no
 * back-reference (see {@link SpriteAnimationRegistry}'s Javadoc).
 */
@Mixin(SpriteContents.class)
abstract class SpriteContentsCaptureAnimatorMixin {

    @org.spongepowered.asm.mixin.Shadow
    public abstract net.minecraft.util.Identifier getId();

    @Inject(method = "createAnimator", at = @At("RETURN"))
    private void lazuli$captureId(GpuBufferSlice slice, int mipLevels,
            CallbackInfoReturnable<SpriteContents.Animator> cir) {
        SpriteAnimationRegistry.register(cir.getReturnValue(), getId().toString());
    }
}
