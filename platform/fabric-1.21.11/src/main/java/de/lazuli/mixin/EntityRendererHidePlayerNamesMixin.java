package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tweaks spec T8 (Hide Player Names): gates the nametag-visibility check
 * every entity renderer already calls before drawing a name label.
 *
 * <p><strong>Confirmed via {@code javap} against this module's own resolved
 * merged Minecraft jar:</strong> {@code EntityRenderer.hasLabel(T, double)}
 * is the boolean gate {@code renderLabelIfPresent} consults, a cleaner
 * cancel-by-{@code false} target than fighting the render-command-queue-
 * based {@code renderLabelIfPresent} method itself.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererHidePlayerNamesMixin {

    @Inject(method = "hasLabel", at = @At("RETURN"), cancellable = true)
    private void lazuli$hideName(Entity entity, double squaredDistanceToCamera,
            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        if (TweakEngineHandoff.require().shouldHideName(Math.sqrt(squaredDistanceToCamera))) {
            cir.setReturnValue(false);
        }
    }
}
