package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tweaks spec T14 (Freecam) {@code showOwnBody} configurable -- 26.2 Mojmap
 * port of {@code fabric-26.1}'s {@code LevelRendererFreecamShowBodyMixin}.
 * Same class Javadoc rationale applies -- see {@code fabric-1.21.11}'s
 * {@code WorldRendererFreecamShowBodyMixin} for the full correction to the
 * spec's own (backwards) Architecture assumption.
 *
 * <p><strong>Real 26.1-vs-26.2 divergence, confirmed via {@code javap}
 * against this module's own resolved merged Minecraft jar</strong> (see
 * {@code .claude/context/minecraft.md}): on 26.2, {@code
 * LevelRenderer.extractVisibleEntities} does not exist at all -- this
 * responsibility (and the entire entity-visibility-extraction pipeline)
 * moved to a new dedicated class, {@code
 * net.minecraft.client.renderer.extract.LevelExtractor}, same method name/
 * signature and the same 4-call-site {@code Camera.entity()} ordinal
 * pattern otherwise unchanged.
 */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorFreecamShowBodyMixin {

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
