package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T13 (No Rain): 26.1 Mojmap equivalent of {@code fabric-1.21.11}'s
 * {@code WeatherRenderingNoRainMixin}. Same class Javadoc rationale applies --
 * see that file.
 *
 * <p><strong>Confirmed via {@code javap} against this module's own resolved
 * merged Minecraft jar</strong>: {@code WeatherRendering} is renamed
 * {@code net.minecraft.client.renderer.WeatherEffectRenderer} on 26.1, with
 * {@code buildPrecipitationPieces}/{@code renderPrecipitation}/
 * {@code addParticlesAndSound} renamed to {@code extractRenderState}/
 * {@code render}/{@code tickRainParticles} respectively (same shapes/
 * behavior, confirmed via {@code javap -c} bytecode trace). {@code
 * WeatherRenderState.rainPieces}/{@code snowPieces} are renamed {@code
 * rainColumns}/{@code snowColumns}, same public-mutable-list shape.
 *
 * <p><strong>26.1-vs-26.2 divergence (see {@code .claude/context/minecraft.md}):
 * this exact mixin does NOT port to 26.2 unchanged</strong> -- 26.2's
 * {@code WeatherEffectRenderer} has no {@code tickRainParticles} method at
 * all; that responsibility moved onto {@code ClientLevel.tickWeatherEffects()}
 * instead. 26.2 needs a second mixin targeting {@code ClientLevel} for sound
 * suppression (see {@code fabric-26.2}'s {@code ClientLevelNoRainMixin}).
 */
@Mixin(WeatherEffectRenderer.class)
abstract class WeatherEffectRendererNoRainMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void lazuli$clearSuppressedColumns(Level level, int radius, float tickProgress, Vec3 cameraPos,
            WeatherRenderState state, CallbackInfo ci) {
        if (!TweakEngineHandoff.require().isNoRainActive()) {
            return;
        }
        state.rainColumns.clear();
        if (TweakEngineHandoff.require().noRainIncludesSnow()) {
            state.snowColumns.clear();
        }
    }

    @Inject(method = "tickRainParticles", at = @At("HEAD"), cancellable = true)
    private void lazuli$cancelSound(ClientLevel level, Camera camera, int ticks, ParticleStatus particleStatus,
            int radius, CallbackInfo ci) {
        if (TweakEngineHandoff.require().isNoRainActive() && TweakEngineHandoff.require().noRainIncludesSound()) {
            ci.cancel();
        }
    }
}
