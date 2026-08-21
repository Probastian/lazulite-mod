package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweaks spec T14 (Freecam) Non-goals: interaction is fully suppressed while
 * Freecam is active -- 26.2 Mojmap port, byte-identical to {@code
 * fabric-26.1}'s copy of this file (confirmed identical {@code
 * startAttack}/{@code startUseItem} shape via {@code javap}) -- see {@code
 * fabric-1.21.11}'s {@code MinecraftClientFreecamSuppressInteractionMixin}
 * for the full rationale.
 */
@Mixin(Minecraft.class)
abstract class MinecraftFreecamSuppressInteractionMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void lazuli$suppressAttack(CallbackInfoReturnable<Boolean> cir) {
        if (TweakEngineHandoff.require().isFreecamActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void lazuli$suppressItemUse(CallbackInfo ci) {
        if (TweakEngineHandoff.require().isFreecamActive()) {
            ci.cancel();
        }
    }
}
