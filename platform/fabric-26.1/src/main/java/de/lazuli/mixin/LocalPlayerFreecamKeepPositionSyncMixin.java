package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tweaks spec T14 (Freecam) -- real bug fix, 26.1 Mojmap port of {@code
 * fabric-26.2}'s mixin of the same name (confirmed identical {@code
 * LocalPlayer.sendPosition()}/{@code isControlledCamera()} bytecode shape
 * via {@code javap -c} against this module's own resolved merged Minecraft
 * jar) -- see that file's Javadoc for the full root-cause rationale (see
 * also {@code .claude/context/minecraft.md} row 112 for the closely-related,
 * already-documented movement-key-routing finding this one extends).
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerFreecamKeepPositionSyncMixin {

    @Shadow
    protected abstract boolean isControlledCamera();

    @Redirect(method = "sendPosition", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isControlledCamera()Z"))
    private boolean lazuli$forcePositionSyncDuringFreecam(LocalPlayer player) {
        return this.isControlledCamera() || TweakEngineHandoff.require().isFreecamActive();
    }
}
