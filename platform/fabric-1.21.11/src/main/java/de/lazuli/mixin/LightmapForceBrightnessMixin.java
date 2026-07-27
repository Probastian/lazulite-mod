package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.render.LightmapTextureManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tweaks spec T2 (Force Brightness): floors the gamma value vanilla reads
 * while rebuilding the per-frame lightmap texture.
 *
 * <p>Redirects the single {@code SimpleOption.getValue()} call inside
 * {@code LightmapTextureManager.update(float)} that reads {@code
 * GameOptions.getGamma()}'s live value (confirmed via {@code javap -c}
 * against this module's own resolved merged Minecraft jar) -- scoped to
 * this one call site inside this one method, so it does not affect the
 * value shown/persisted by the options screen.
 */
@Mixin(LightmapTextureManager.class)
abstract class LightmapForceBrightnessMixin {

    @Redirect(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/option/SimpleOption;getValue()Ljava/lang/Object;"))
    private Object lazuli$forceBrightness(SimpleOption<?> option) {
        Object value = option.getValue();
        var hooks = TweakEngineHandoff.require();
        if (hooks.isForceBrightnessActive() && value instanceof Double gamma) {
            return Math.max(gamma, (double) hooks.minBrightness());
        }
        return value;
    }
}
