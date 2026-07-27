package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;
import de.lazuli.tweaks.TweakHooksImpl;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T5 (Custom Crosshair): cancels vanilla's crosshair draw and
 * draws a custom one from the tweak's outline/gap/length/thickness/
 * centerDot/RGB configurables.
 *
 * <p><strong>Confirmed via {@code javap} against this module's own resolved
 * merged Minecraft jar:</strong> {@code InGameHud.renderCrosshair(
 * DrawContext, RenderTickCounter)} is the direct, cancellable draw call on
 * this platform (unlike 26.1/26.2's extraction-model equivalents).
 */
@Mixin(InGameHud.class)
abstract class InGameHudCustomCrosshairMixin {

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void lazuli$customCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        TweakHooksImpl hooks = TweakEngineHandoff.require();
        if (!hooks.isCustomCrosshairActive()) {
            return;
        }
        ci.cancel();

        boolean outline = Boolean.TRUE.equals(hooks.crosshairConfigurable("outline"));
        double gap = numOr(hooks.crosshairConfigurable("gap"), 3.0);
        double length = numOr(hooks.crosshairConfigurable("length"), 6.0);
        double thickness = numOr(hooks.crosshairConfigurable("thickness"), 1.0);
        boolean centerDot = Boolean.TRUE.equals(hooks.crosshairConfigurable("centerDot"));
        int r = (int) numOr(hooks.crosshairConfigurable("colorR"), 255.0);
        int g = (int) numOr(hooks.crosshairConfigurable("colorG"), 255.0);
        int b = (int) numOr(hooks.crosshairConfigurable("colorB"), 255.0);
        int color = 0xFF000000 | (r << 16) | (g << 8) | b;

        var window = MinecraftClient.getInstance().getWindow();
        int cx = window.getScaledWidth() / 2;
        int cy = window.getScaledHeight() / 2;

        lazuli$drawCross(context, cx, cy, gap, length, thickness, color, outline, centerDot);
    }

    @Unique
    private static double numOr(Object raw, double fallback) {
        return raw instanceof Number n ? n.doubleValue() : fallback;
    }

    @Unique
    private static void lazuli$drawCross(DrawContext context, int cx, int cy, double gap, double length,
            double thickness, int color, boolean outline, boolean centerDot) {
        int half = (int) Math.max(1, thickness / 2);
        int g = (int) gap;
        int l = (int) length;

        if (outline) {
            int oc = 0xFF000000;
            context.fill(cx - g - l - 1, cy - half - 1, cx - g + 1, cy + half + 1, oc);
            context.fill(cx + g - 1, cy - half - 1, cx + g + l + 1, cy + half + 1, oc);
            context.fill(cx - half - 1, cy - g - l - 1, cx + half + 1, cy - g + 1, oc);
            context.fill(cx - half - 1, cy + g - 1, cx + half + 1, cy + g + l + 1, oc);
        }

        // Left / right arms.
        context.fill(cx - g - l, cy - half, cx - g, cy + half, color);
        context.fill(cx + g, cy - half, cx + g + l, cy + half, color);
        // Top / bottom arms.
        context.fill(cx - half, cy - g - l, cx + half, cy - g, color);
        context.fill(cx - half, cy + g, cx + half, cy + g + l, color);

        if (centerDot) {
            context.fill(cx - half, cy - half, cx + half, cy + half, color);
        }
    }
}
