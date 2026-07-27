package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.util.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Tweaks spec T9 (Clear Water): multiplies the underwater overlay's alpha
 * by the configured opacity multiplier.
 *
 * <p><strong>Confirmed via {@code javap} against this module's own resolved
 * merged Minecraft jar (see {@code docs/specs/tweaks-hooks-wiring.md}'s
 * "Findings -- javap verification pass"):</strong> {@code InGameHud} has no
 * dedicated underwater-overlay method -- water/portal/spyglass/nausea/
 * powder-snow overlays all funnel through the single shared {@code
 * renderOverlay(DrawContext, Identifier, float)}, so the mixin must
 * discriminate by the {@code Identifier} argument. The exact underwater
 * overlay texture id was not resolved to a single named constant by the
 * javap pass (unlike {@code NAUSEA_TEXTURE}/{@code POWDER_SNOW_OUTLINE},
 * which are named constants -- the water overlay is applied via a
 * differently-derived path); this mixin uses a path-substring heuristic
 * ({@code "underwater"}) as a pragmatic discriminator, flagged here for
 * verification-phase scrutiny rather than presented as a fully confirmed
 * exact-identifier match.
 */
@Mixin(InGameHud.class)
abstract class InGameHudClearWaterMixin {

    @ModifyVariable(method = "renderOverlay", at = @At("HEAD"), argsOnly = true)
    private float lazuli$clearWater(float alpha, DrawContext context, Identifier overlayTexture) {
        if (overlayTexture.getPath().toLowerCase(java.util.Locale.ROOT).contains("underwater")) {
            return alpha * TweakEngineHandoff.require().underwaterOverlayOpacityMultiplier();
        }
        return alpha;
    }
}
