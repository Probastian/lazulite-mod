package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tweaks spec T14 (Freecam) -- real bug fix, not part of the Addendum
 * AD-1..AD-5 corrective pass: pressing the sneak key while Freecam is
 * active still crouches the real player, because {@code
 * Player.getDesiredPose()} (private, called every tick from {@code
 * updatePlayerPose()}) reads {@code isShiftKeyDown()} unconditionally --
 * confirmed via {@code javap -c} against this module's own resolved merged
 * Minecraft jar -- independent of the {@code isControlledCamera()} gate
 * that already suppresses ordinary WASD/rotation input application to the
 * real player while Freecam owns the camera (row 112, AD-1).
 *
 * <p>Fix: redirect this ONE call site so it reports "not holding sneak" for
 * the real (client-controlled) player specifically while Freecam is active,
 * leaving every other {@code isShiftKeyDown()} call site (and every other
 * entity's sneak state) untouched.
 */
@Mixin(Player.class)
abstract class PlayerFreecamSuppressSneakMixin {

    @Redirect(method = "getDesiredPose", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isShiftKeyDown()Z"))
    private boolean lazuli$suppressSneakDuringFreecam(Player player) {
        if (player instanceof LocalPlayer && TweakEngineHandoff.require().isFreecamActive()) {
            return false;
        }
        return player.isShiftKeyDown();
    }
}
