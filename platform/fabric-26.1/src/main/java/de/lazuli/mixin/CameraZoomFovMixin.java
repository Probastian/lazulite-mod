package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweaks spec T11 (Zoom): applies {@code TweakHooksImpl.applyFov(float)} to
 * every value vanilla uses as the frame's rendered FOV.
 *
 * <p><strong>Real choke point, confirmed via {@code javap} against this
 * module's own resolved (Mojang-mapped) merged Minecraft jar (see
 * {@code docs/specs/tweaks-zoom-fov-fix.md}, correcting
 * {@code docs/specs/tweaks-zoom-fov.md}'s original Architecture section):
 * </strong> {@code Camera.getFov()} is real but is never called on the
 * render path ({@code GameRenderer} never invokes it). The value that
 * actually reaches the screen is produced by the private
 * {@code Camera.calculateFov(float)}, called exactly once per frame from
 * {@code Camera.update(DeltaTracker)}, whose return value is assigned
 * directly into {@code this.fov} and consumed one statement later by
 * {@code setupPerspective} for the world-render projection. Intercepting
 * {@code calculateFov(float)} instead is the correct single choke point for
 * the world-render FOV; {@code calculateHudFov}/{@code hudFov} (held-item
 * FOV) is intentionally left untouched (see fix spec's Non-goals).
 */
@Mixin(Camera.class)
abstract class CameraZoomFovMixin {

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void lazuli$applyZoom(float tickDelta, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(TweakEngineHandoff.require().applyFov(cir.getReturnValue()));
    }
}
