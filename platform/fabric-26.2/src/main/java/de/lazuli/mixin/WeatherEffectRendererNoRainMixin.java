package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T13 (No Rain): 26.2 Mojmap equivalent of {@code fabric-1.21.11}'s
 * {@code WeatherRenderingNoRainMixin} / {@code fabric-26.1}'s {@code
 * WeatherEffectRendererNoRainMixin} -- visual suppression half only. Same
 * class Javadoc rationale applies -- see {@code fabric-1.21.11}'s copy.
 *
 * <p><strong>Confirmed via {@code javap} against this module's own resolved
 * merged Minecraft jar</strong>: {@code WeatherEffectRenderer.extractRenderState}
 * exists on 26.2 too but with a narrower signature than 26.1's (no {@code int}
 * radius parameter, {@code Level} narrowed to {@code ClientLevel} --
 * confirmed real 26.1-vs-26.2 divergence, not just a rename; see
 * {@code .claude/context/minecraft.md}). {@code WeatherRenderState.rainColumns}/
 * {@code snowColumns} are unchanged from 26.1's shape.
 *
 * <p><strong>26.2 has no {@code tickRainParticles} method on {@code
 * WeatherEffectRenderer} at all</strong> -- sound suppression is handled by
 * the sibling {@code ClientLevelNoRainMixin} targeting {@code
 * ClientLevel.tickWeatherEffects()} instead (see that class's Javadoc).
 */
@Mixin(WeatherEffectRenderer.class)
abstract class WeatherEffectRendererNoRainMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void lazuli$clearSuppressedColumns(ClientLevel level, float tickProgress, Vec3 cameraPos,
            WeatherRenderState state, CallbackInfo ci) {
        if (!TweakEngineHandoff.require().isNoRainActive()) {
            return;
        }
        state.rainColumns.clear();
        if (TweakEngineHandoff.require().noRainIncludesSnow()) {
            state.snowColumns.clear();
        }
    }
}
