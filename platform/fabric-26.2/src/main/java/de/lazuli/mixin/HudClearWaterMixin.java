package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Tweaks spec T9 (Clear Water): multiplies the underwater overlay's alpha
 * by the configured opacity multiplier.
 *
 * <p><strong>Confirmed via {@code javap}:</strong> on 26.2 the crosshair/
 * overlay/boss-bar extraction methods live on a new {@code Hud} class
 * (held by {@code Gui.hud}), not {@code Gui} itself -- {@code
 * Gui.extractTextureOverlay} does not exist on 26.2. Method name/signature
 * inside {@code Hud} is otherwise identical to 26.1's {@code Gui}. Same
 * shared-overlay-method discrimination requirement/heuristic caveat as the
 * 26.1 mixin.
 */
@Mixin(Hud.class)
abstract class HudClearWaterMixin {

    @ModifyVariable(method = "extractTextureOverlay", at = @At("HEAD"), argsOnly = true)
    private float lazuli$clearWater(float alpha, GuiGraphicsExtractor extractor, Identifier overlayTexture) {
        if (overlayTexture.getPath().toLowerCase(java.util.Locale.ROOT).contains("underwater")) {
            return alpha * TweakEngineHandoff.require().underwaterOverlayOpacityMultiplier();
        }
        return alpha;
    }
}
