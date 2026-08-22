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
 * Tweaks spec T14 (Freecam) Addendum AD-1 -- 26.2 Mojmap port of {@code
 * fabric-26.1}'s {@code MouseHandlerFreecamLookRedirectMixin} (confirmed
 * byte-identical target class/method/signature via {@code javap -c} against
 * this module's own resolved merged Minecraft jar). See {@code
 * fabric-1.21.11}'s {@code MouseFreecamLookRedirectMixin} for the full class
 * Javadoc rationale.
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
