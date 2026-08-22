package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tweaks spec T14 (Freecam) -- real bug fix, Yarn port of {@code
 * fabric-26.2}'s {@code PlayerFreecamSuppressSneakMixin} (confirmed
 * equivalent {@code PlayerEntity.getExpectedPose()}/{@code isSneaking()}
 * bytecode shape via {@code javap -c} against this module's own resolved
 * merged Minecraft jar) -- see that file's Javadoc for the full root-cause
 * rationale.
 */
@Mixin(PlayerEntity.class)
abstract class PlayerEntityFreecamSuppressSneakMixin {

    @Redirect(method = "getExpectedPose", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/PlayerEntity;isSneaking()Z"))
    private boolean lazuli$suppressSneakDuringFreecam(PlayerEntity player) {
        if (player instanceof ClientPlayerEntity && TweakEngineHandoff.require().isFreecamActive()) {
            return false;
        }
        return player.isSneaking();
    }
}
