package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T9 (Clear Water): reduces the underwater visual murkiness by
 * the configured opacity multiplier.
 *
 * <p><strong>Bug fix, re-confirmed via {@code javap} against this module's
 * own resolved Mojang-mapped merged Minecraft jar:</strong> the previous
 * revision of this mixin targeted {@code Gui.extractTextureOverlay(
 * GuiGraphicsExtractor, Identifier, float)} on the (incorrect) assumption
 * that underwater shared 26.x's generic overlay-texture extraction call
 * the same way portal/spyglass/nausea/powder-snow/pumpkin do. It does
 * not: {@code javap}-disassembling {@code Gui.extractCameraOverlays}
 * (the method that drives every one of those) shows it never references
 * an underwater texture or calls {@code extractTextureOverlay} for it --
 * on this platform, "clear water" isn't a screen-space overlay at all
 * any more. Underwater visibility is produced by a dedicated fog
 * environment, {@code net.minecraft.client.renderer.fog.environment.
 * WaterFogEnvironment}, whose {@code setupFog(FogData, ...)} sets the
 * near/far fog distances applied while the camera is submerged. The old
 * mixin's target method was simply never invoked for underwater on this
 * platform, which is why it had zero observable effect.
 *
 * <p>This mixin instead scales {@code FogData.environmentalStart}/
 * {@code environmentalEnd} outward as the opacity multiplier drops toward
 * 0.0 (fully clear -- fog pushed far away), leaving them untouched at
 * 1.0 (vanilla default). A configured multiplier of 0 is treated as "as
 * clear as practical" rather than a literal divide-by-zero.
 */
@Mixin(WaterFogEnvironment.class)
abstract class WaterFogEnvironmentClearWaterMixin {

    @Inject(method = "setupFog", at = @At("RETURN"))
    private void lazuli$clearWater(FogData data, Camera camera, ClientLevel level, float renderDistance,
            DeltaTracker deltaTracker, CallbackInfo ci) {
        float multiplier = TweakEngineHandoff.require().underwaterOverlayOpacityMultiplier();
        if (multiplier >= 1.0f) {
            return;
        }
        float scale = multiplier <= 0.0001f ? 1000.0f : (1.0f / multiplier);
        data.environmentalStart *= scale;
        data.environmentalEnd *= scale;
    }
}
