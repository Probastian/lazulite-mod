package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweaks spec T11 (Zoom): applies {@code TweakHooksImpl.applyFov(float)} to
 * every value vanilla uses as the frame's rendered FOV -- {@code
 * fabric-1.21.11} (Yarn-mapped) port.
 *
 * <p><strong>Single choke point, confirmed via {@code javap} against this
 * module's own resolved merged Minecraft jar (see
 * {@code docs/specs/tweaks-zoom-fov.md}'s Architecture section):</strong>
 * {@code GameRenderer#getFov(Camera, float, boolean)} is the sole method all
 * four of vanilla's own internal FOV call sites funnel through on this
 * version, so intercepting only this private method covers every rendered/
 * projected FOV use without needing to mixin into each individual call site.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererZoomFovMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void lazuli$applyZoom(Camera camera, float tickDelta, boolean changingFov,
            CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(TweakEngineHandoff.require().applyFov(cir.getReturnValue()));
    }
}
