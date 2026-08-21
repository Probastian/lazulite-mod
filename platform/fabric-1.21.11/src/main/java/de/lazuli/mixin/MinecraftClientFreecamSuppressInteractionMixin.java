package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.MinecraftClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweaks spec T14 (Freecam) Non-goals: block/entity interaction is fully
 * suppressed while Freecam is active (the spec's own stated default,
 * Open Question 1 -- vanilla's interaction raycast originates from the
 * camera's position, which would otherwise let the player remotely mine/
 * attack from wherever the detached camera currently is).
 *
 * <p><strong>Confirmed via {@code javap} against this module's own resolved
 * merged Minecraft jar</strong> (this exact target was not analyzed by the
 * spec at all, per the plan's own Risk callout): {@code
 * MinecraftClient.doAttack()} ({@code private boolean}, left/attack click)
 * and {@code MinecraftClient.doItemUse()} ({@code private void}, right/use
 * click) are the two per-tick entry points {@code handleInputEvents()}
 * calls, exactly matching the plan's own candidate guess.
 */
@Mixin(MinecraftClient.class)
abstract class MinecraftClientFreecamSuppressInteractionMixin {

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void lazuli$suppressAttack(CallbackInfoReturnable<Boolean> cir) {
        if (TweakEngineHandoff.require().isFreecamActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void lazuli$suppressItemUse(CallbackInfo ci) {
        if (TweakEngineHandoff.require().isFreecamActive()) {
            ci.cancel();
        }
    }
}
