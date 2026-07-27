package de.lazuli.mixin;

import de.lazuli.tweaks.SpriteAnimationRegistry;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

import net.minecraft.client.renderer.texture.SpriteContents;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweaks spec T6 (Disable Animations): stashes the owning sprite id for
 * each created {@code AnimationState} (see {@link SpriteAnimationRegistry}'s
 * Javadoc) via the public {@code SpriteContents.createAnimationState(...)}
 * entry point, which delegates to the private {@code AnimatedTexture}'s own
 * overload -- confirmed identical shape on 26.1/26.2.
 */
@Mixin(SpriteContents.class)
abstract class SpriteContentsCaptureAnimationStateMixin {

    @org.spongepowered.asm.mixin.Shadow
    public abstract net.minecraft.resources.Identifier name();

    @Inject(method = "createAnimationState", at = @At("RETURN"))
    private void lazuli$captureId(GpuBufferSlice slice, int mipLevels,
            CallbackInfoReturnable<SpriteContents.AnimationState> cir) {
        SpriteAnimationRegistry.register(cir.getReturnValue(), name().toString());
    }
}
