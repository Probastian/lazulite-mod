package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tweaks spec T14 (Freecam) {@code showOwnBody} configurable -- 26.1 Mojmap
 * port of {@code fabric-1.21.11}'s {@code WorldRendererFreecamShowBodyMixin}.
 * Same class Javadoc rationale applies -- see that file for the full
 * correction to the spec's own (backwards) Architecture assumption.
 *
 * <p><strong>Confirmed via {@code javap -c} against this module's own
 * resolved merged Minecraft jar:</strong> the equivalent check lives in
 * {@code LevelRenderer.extractVisibleEntities(Camera, Frustum, DeltaTracker,
 * LevelRenderState)}, same 4-call-site {@code Camera.entity()} ordinal
 * pattern as 1.21.11's {@code Camera.getFocusedEntity()} (Mojmap method name
 * is {@code entity()}, not {@code getFocusedEntity()}); {@code
 * ClientPlayerEntity} is {@code net.minecraft.client.player.LocalPlayer} on
 * this mapping.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererFreecamShowBodyMixin {

    @Redirect(method = "extractVisibleEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;entity()Lnet/minecraft/world/entity/Entity;",
            ordinal = 3))
    private Entity lazuli$spoofFocusedEntityForOwnBody(Camera camera) {
        Entity real = camera.entity();
        var hooks = TweakEngineHandoff.require();
        if (hooks.isFreecamActive() && hooks.freecamShowOwnBody()) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                return client.player;
            }
        }
        return real;
    }
}
