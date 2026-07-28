package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.MouseHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T11 (Zoom), "scrollToAdjust" configurable: while Zoom is active
 * and this configurable is on, mouse-wheel scroll adjusts the magnification
 * instead of vanilla's own scroll handling (hotbar slot cycling / GUI
 * scroll) -- this module's own resolved (Mojang-mapped) merged Minecraft
 * jar port.
 *
 * <p><strong>Single choke point, confirmed via {@code javap} against this
 * module's own resolved merged Minecraft jar:</strong> the private {@code
 * MouseHandler#onScroll(long, double, double)} is the sole handler wired to
 * GLFW's scroll callback and is where vanilla itself branches into GUI
 * scroll vs. hotbar-slot scroll. Injecting at {@code HEAD} and cancelling
 * lets Zoom's scroll-to-adjust take priority over both without needing a
 * mixin into either downstream branch.</p>
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerScrollZoomMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void lazuli$adjustZoomByScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (TweakEngineHandoff.require().adjustZoomByScroll(vertical)) {
            ci.cancel();
        }
    }
}
