package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweaks spec T8 (Hide Player Names): gates the nametag-visibility check
 * every entity renderer already calls before drawing a name label.
 *
 * <p><strong>Confirmed via {@code javap} against this module's own resolved
 * merged Minecraft jar -- corrects the spec/plan's guessed {@code
 * renderNameTag}, which does not exist:</strong> {@code
 * EntityRenderer.shouldShowName(T, double)} is the real boolean gate
 * (confirmed identical on 26.1 and 26.2).
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererHidePlayerNamesMixin {

    @Inject(method = "shouldShowName", at = @At("RETURN"), cancellable = true)
    private void lazuli$hideName(Entity entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        if (TweakEngineHandoff.require().shouldHideName(Math.sqrt(distanceToCameraSq))) {
            cir.setReturnValue(false);
        }
    }
}
