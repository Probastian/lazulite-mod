package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;
import de.lazuli.tweaks.TweakHooksImpl;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T5 (Custom Crosshair): cancels vanilla's crosshair state
 * extraction and draws a custom one from the tweak's outline/gap/length/
 * thickness/centerDot/RGB configurables.
 *
 * <p><strong>Confirmed via {@code javap}, corrects the spec/plan's guessed
 * cancel-and-redraw shape:</strong> {@code Gui} has no {@code
 * renderCrosshair} -- the real target is {@code Gui.extractCrosshair(
 * GuiGraphicsExtractor, DeltaTracker)}, part of the render-state-extraction
 * model. This mixin cancels the extraction step and writes the custom
 * crosshair's quads directly via {@code GuiGraphicsExtractor.fill(...)}
 * (confirmed present with the same {@code (x1,y1,x2,y2,color)} signature
 * used by {@code DrawContext.fill} on 1.21.11).
 */
@Mixin(Hud.class)
abstract class HudCustomCrosshairMixin {

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void lazuli$customCrosshair(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        TweakHooksImpl hooks = TweakEngineHandoff.require();
        if (!hooks.isCustomCrosshairActive()) {
            return;
        }
        ci.cancel();
        CrosshairPainter.paint(extractor, hooks);
    }

    @Unique
    static final class CrosshairPainter {
        private CrosshairPainter() {
        }

        static void paint(GuiGraphicsExtractor extractor, TweakHooksImpl hooks) {
            boolean outline = Boolean.TRUE.equals(hooks.crosshairConfigurable("outline"));
            double gap = numOr(hooks.crosshairConfigurable("gap"), 3.0);
            double length = numOr(hooks.crosshairConfigurable("length"), 6.0);
            double thickness = numOr(hooks.crosshairConfigurable("thickness"), 1.0);
            boolean centerDot = Boolean.TRUE.equals(hooks.crosshairConfigurable("centerDot"));
            int r = (int) numOr(hooks.crosshairConfigurable("colorR"), 255.0);
            int g = (int) numOr(hooks.crosshairConfigurable("colorG"), 255.0);
            int b = (int) numOr(hooks.crosshairConfigurable("colorB"), 255.0);
            int color = 0xFF000000 | (r << 16) | (g << 8) | b;

            int cx = extractor.guiWidth() / 2;
            int cy = extractor.guiHeight() / 2;
            int half = (int) Math.max(1, thickness / 2);
            int gi = (int) gap;
            int l = (int) length;

            if (outline) {
                int oc = 0xFF000000;
                extractor.fill(cx - gi - l - 1, cy - half - 1, cx - gi + 1, cy + half + 1, oc);
                extractor.fill(cx + gi - 1, cy - half - 1, cx + gi + l + 1, cy + half + 1, oc);
                extractor.fill(cx - half - 1, cy - gi - l - 1, cx + half + 1, cy - gi + 1, oc);
                extractor.fill(cx - half - 1, cy + gi - 1, cx + half + 1, cy + gi + l + 1, oc);
            }

            extractor.fill(cx - gi - l, cy - half, cx - gi, cy + half, color);
            extractor.fill(cx + gi, cy - half, cx + gi + l, cy + half, color);
            extractor.fill(cx - half, cy - gi - l, cx + half, cy - gi, color);
            extractor.fill(cx - half, cy + gi, cx + half, cy + gi + l, color);

            if (centerDot) {
                extractor.fill(cx - half, cy - half, cx + half, cy + half, color);
            }
        }

        private static double numOr(Object raw, double fallback) {
            return raw instanceof Number n ? n.doubleValue() : fallback;
        }
    }
}
