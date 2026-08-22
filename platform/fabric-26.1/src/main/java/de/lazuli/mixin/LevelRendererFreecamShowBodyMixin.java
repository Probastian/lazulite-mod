package de.lazuli.mixin;

import de.lazuli.tweaks.FreecamTicker;
import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tweaks spec T14 (Freecam) "show own body" behavior -- 26.1 Mojmap port of
 * {@code fabric-1.21.11}'s {@code WorldRendererFreecamShowBodyMixin}. Same
 * class Javadoc rationale applies -- see that file for the full correction
 * to the spec's own (backwards) Architecture assumption.
 *
 * <p><strong>Confirmed via {@code javap -c} against this module's own
 * resolved merged Minecraft jar:</strong> the equivalent check lives in
 * {@code LevelRenderer.extractVisibleEntities(Camera, Frustum, DeltaTracker,
 * LevelRenderState)}, same 4-call-site {@code Camera.entity()} ordinal
 * pattern as 1.21.11's {@code Camera.getFocusedEntity()} (Mojmap method name
 * is {@code entity()}, not {@code getFocusedEntity()}); {@code
 * ClientPlayerEntity} is {@code net.minecraft.client.player.LocalPlayer} on
 * this mapping.
 *
 * <p><strong>Addendum AD-2:</strong> the manual {@code showOwnBody}
 * configurable is removed -- the body now shows automatically whenever the
 * freecam camera's live position is outside the player's own (inflated)
 * live bounding box, computed once per tick by {@link FreecamTicker} and
 * read here in place of the removed {@code FreecamHook.freecamShowOwnBody()}.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererFreecamShowBodyMixin {

    @Redirect(method = "extractVisibleEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;entity()Lnet/minecraft/world/entity/Entity;",
            ordinal = 3))
    private Entity lazuli$spoofFocusedEntityForOwnBody(Camera camera) {
        Entity real = camera.entity();
        var hooks = TweakEngineHandoff.require();
        if (hooks.isFreecamActive() && !FreecamTicker.isCameraInsidePlayerBounds()) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                return client.player;
            }
        }
        return real;
    }
}
