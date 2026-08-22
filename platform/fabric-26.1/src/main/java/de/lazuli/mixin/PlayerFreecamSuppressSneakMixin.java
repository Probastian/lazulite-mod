package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tweaks spec T14 (Freecam) -- real bug fix, 26.1 Mojmap port of {@code
 * fabric-26.2}'s mixin of the same name (confirmed identical {@code
 * Player.getDesiredPose()}/{@code isShiftKeyDown()} bytecode shape via
 * {@code javap -c} against this module's own resolved merged Minecraft
 * jar) -- see that file's Javadoc for the full root-cause rationale.
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
