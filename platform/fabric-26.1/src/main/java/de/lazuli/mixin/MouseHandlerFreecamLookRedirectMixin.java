package de.lazuli.mixin;

import de.lazuli.tweaks.FreecamCameraEntity;
import de.lazuli.tweaks.FreecamTicker;
import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tweaks spec T14 (Freecam) Addendum AD-1 -- 26.1 Mojmap port of {@code
 * fabric-1.21.11}'s {@code MouseFreecamLookRedirectMixin}. Same class
 * Javadoc rationale applies -- see that file.
 *
 * <p><strong>Real per-platform divergence, confirmed via {@code javap -c}
 * against this module's own resolved merged Minecraft jar:</strong> the
 * private {@code turnPlayer(double)} takes a single {@code double} (a time
 * delta), not the raw (dx, dy) pair Yarn's {@code updateMouse(double)} also
 * takes -- {@code turnPlayer} itself computes the smoothed/scaled (dx, dy)
 * internally and calls {@code LocalPlayer.turn(dx, dy)} once at the end.
 * {@code turnPlayer} is confirmed {@code private} on both 26.1 and 26.2 --
 * the spec's own draft guessed {@code public}, which was wrong; {@code
 * @Redirect} targets the inner {@code turn} call regardless, so this
 * mixin's own target method's visibility does not matter. {@code
 * Entity.turn(double, double)} (Mojmap name for Yarn's {@code
 * changeLookDirection}) is confirmed declared directly on {@code Entity},
 * so {@link FreecamCameraEntity} already inherits it with no new method
 * needed.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerFreecamLookRedirectMixin {

    @Redirect(method = "turnPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void lazuli$redirectTurnToCamera(LocalPlayer player, double dx, double dy) {
        if (TweakEngineHandoff.require().isFreecamActive()) {
            FreecamCameraEntity camera = FreecamTicker.cameraEntity();
            if (camera != null) {
                camera.turn(dx, dy);
                return;
            }
        }
        player.turn(dx, dy);
    }
}
