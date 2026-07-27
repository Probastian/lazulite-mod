package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Tweaks spec T9 (Clear Water): multiplies the underwater overlay's alpha
 * by the configured opacity multiplier.
 *
 * <p><strong>Confirmed via {@code javap}:</strong> 26.1's overlay extraction
 * lives on {@code Gui.extractTextureOverlay(GuiGraphicsExtractor,
 * Identifier, float)} -- shared with portal/spyglass/nausea/powder-snow
 * overlays, same discrimination requirement and same path-substring
 * heuristic caveat as the 1.21.11 mixin (see its Javadoc).
 */
@Mixin(Gui.class)
abstract class GuiClearWaterMixin {

    @ModifyVariable(method = "extractTextureOverlay", at = @At("HEAD"), argsOnly = true)
    private float lazuli$clearWater(float alpha, GuiGraphicsExtractor extractor, Identifier overlayTexture) {
        if (overlayTexture.getPath().toLowerCase(java.util.Locale.ROOT).contains("underwater")) {
            return alpha * TweakEngineHandoff.require().underwaterOverlayOpacityMultiplier();
        }
        return alpha;
    }
}
