package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T2 (Force Brightness): floors the extracted lightmap
 * brightness after vanilla computes it for the frame.
 *
 * <p><strong>Real target confirmed via {@code javap} against this module's
 * own resolved merged Minecraft jar -- corrects the spec/plan's guessed
 * {@code LightTexture.updateLightTexture(float)}, which does not exist on
 * 26.1/26.2.</strong> 26.x's render-state-extraction refactor (matching the
 * pattern already documented for T5/T9/T12 elsewhere) moved this
 * responsibility to {@code LightmapRenderStateExtractor.extract
 * (LightmapRenderState, float)}, which writes the gamma-derived value into
 * the mutable {@code LightmapRenderState.brightness} public field passed by
 * reference -- confirmed identical shape on 26.1 and 26.2.
 */
@Mixin(LightmapRenderStateExtractor.class)
abstract class LightmapRenderStateExtractorForceBrightnessMixin {

    @Inject(method = "extract", at = @At("RETURN"))
    private void lazuli$forceBrightness(LightmapRenderState state, float tickDelta, CallbackInfo ci) {
        var hooks = TweakEngineHandoff.require();
        if (hooks.isForceBrightnessActive()) {
            float boosted = Math.max(state.brightness, hooks.minBrightness());
            if (boosted != state.brightness) {
                state.brightness = boosted;
                // Lightmap.render(state) no-ops unless needsUpdate is set, so
                // without this the boosted value is silently dropped on every
                // frame where vanilla itself didn't already flag a change.
                state.needsUpdate = true;
            }
        }
    }
}
