package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tweaks spec T14 (Freecam) {@code showOwnBody} configurable.
 *
 * <p><strong>Real, load-bearing correction to the spec's own Architecture
 * assumption, found via {@code javap -c} bytecode trace against this
 * module's own resolved merged Minecraft jar:</strong> the spec assumed
 * {@code showOwnBody = true} (the default) falls out of {@code
 * setCameraEntity} "for free" and only {@code showOwnBody = false} needs a
 * mixin. The real vanilla logic in {@code WorldRenderer.fillEntityRenderStates}
 * is the OPPOSITE: once the camera's focused entity is not {@code
 * client.player}, vanilla's own {@code entity instanceof ClientPlayerEntity
 * && camera.getFocusedEntity() != entity} check unconditionally SKIPS
 * rendering the real player's body (this is the same code path that keeps a
 * spectated player invisible while spectating someone else) -- so {@code
 * showOwnBody = false} is what already happens for free once Freecam
 * detaches the camera, and {@code showOwnBody = true} is the one direction
 * that needs this mixin.
 *
 * <p>Targets the 4th (0-indexed 3rd) of {@code fillEntityRenderStates}'s
 * four {@code Camera.getFocusedEntity()} call sites -- the one feeding that
 * specific {@code instanceof ClientPlayerEntity} check -- confirmed via
 * {@code javap -c} to be a stable ordinal position not shared with the
 * earlier, unrelated "hide own body in ordinary first-person view" check
 * (which stays untouched, so normal non-Freecam first-person hiding is
 * unaffected). No local-variable capture is needed: the only object in this
 * loop that can ever satisfy {@code instanceof ClientPlayerEntity} is
 * {@code client.player} itself (there is exactly one per client), so
 * spoofing the return value to {@code client.player} whenever Freecam is
 * active and {@code showOwnBody} is true makes that equality check always
 * pass, without needing to know which entity the loop is currently on.
 */
@Mixin(WorldRenderer.class)
abstract class WorldRendererFreecamShowBodyMixin {

    @Redirect(method = "fillEntityRenderStates", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/Camera;getFocusedEntity()Lnet/minecraft/entity/Entity;",
            ordinal = 3))
    private Entity lazuli$spoofFocusedEntityForOwnBody(Camera camera) {
        Entity real = camera.getFocusedEntity();
        var hooks = TweakEngineHandoff.require();
        if (hooks.isFreecamActive() && hooks.freecamShowOwnBody()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                return client.player;
            }
        }
        return real;
    }
}
