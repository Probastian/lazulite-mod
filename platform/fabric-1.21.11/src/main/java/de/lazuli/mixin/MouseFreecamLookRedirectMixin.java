package de.lazuli.mixin;

import de.lazuli.tweaks.FreecamCameraEntity;
import de.lazuli.tweaks.FreecamTicker;
import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tweaks spec T14 (Freecam) Addendum AD-1: the real player's own yaw/pitch
 * must not change as a result of mouse input processed while Freecam is
 * active -- only the detached camera's own rotation should.
 *
 * <p><strong>Root cause (spec AD-1), confirmed via {@code javap -c} against
 * this module's own resolved merged Minecraft jar:</strong> {@code
 * Mouse.updateMouse(double)} unconditionally calls {@code
 * client.player.changeLookDirection(dx, dy)} every frame -- this call site
 * hard-codes {@code client.player}, it does NOT key off {@code
 * MinecraftClient.getCameraEntity()} the way row 112's movement-key finding
 * did, so Freecam's existing {@code setCameraEntity} redirect has no effect
 * on it. {@code changeLookDirection(double, double)} itself is confirmed
 * declared on {@code Entity} (not {@code ClientPlayerEntity}) -- javac just
 * emits the compile-time receiver type in the invoke instruction -- so
 * {@link FreecamCameraEntity} (itself an {@code Entity} subclass) already
 * inherits the identical method with no new "turn" method needed on it.
 *
 * <p>Fix: redirect this one call site. While Freecam is active and a camera
 * entity exists, apply the frame's raw look delta to the camera instead of
 * the player -- the player's own {@code changeLookDirection} is not called
 * at all for that frame, so its yaw/pitch fields never move while Freecam is
 * active, matching spec AD-1's pinned-snapshot requirement exactly (the
 * snapshot is simply "whatever the fields already were," never touched).
 * When Freecam is inactive (or no camera exists yet), this falls back to the
 * original unconditional {@code player.changeLookDirection(dx, dy)} call --
 * mouse-look resumes controlling the player from wherever its fields already
 * are, no snap.
 */
@Mixin(Mouse.class)
abstract class MouseFreecamLookRedirectMixin {

    @Redirect(method = "updateMouse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void lazuli$redirectLookDirectionToCamera(ClientPlayerEntity player, double dx, double dy) {
        if (TweakEngineHandoff.require().isFreecamActive()) {
            FreecamCameraEntity camera = FreecamTicker.cameraEntity();
            if (camera != null) {
                camera.changeLookDirection(dx, dy);
                return;
            }
        }
        player.changeLookDirection(dx, dy);
    }
}
