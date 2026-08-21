package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WeatherRendering;
import net.minecraft.client.render.state.WeatherRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticlesMode;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T13 (No Rain): suppresses the rain/snow precipitation overlay
 * and its ambient sound loop while leaving lightning fully untouched
 * (spec Non-goals -- {@link WeatherRendering} never references lightning in
 * any of the methods this mixin targets, confirmed via {@code javap}).
 *
 * <p><strong>Confirmed via {@code javap} against this module's own resolved
 * merged Minecraft jar</strong> (1.21.11+build.6, superseding the spec's own
 * medium-confidence guess for this exact pin): {@code WeatherRendering} has
 * the exact {@code buildPrecipitationPieces}/{@code renderPrecipitation}/
 * {@code addParticlesAndSound} shape the spec predicted, with one real
 * signature deviation -- {@code addParticlesAndSound} takes a trailing extra
 * {@code int} parameter beyond the spec's own 4-arg guess.
 *
 * <p><strong>Deviation from the spec's two named alternatives for the
 * {@code includeSnow} discriminator (private {@code getPrecipitationAt}
 * {@code @Invoker} vs. duplicating the public {@code Biome.getPrecipitation}
 * lookup):</strong> {@code javap} also revealed {@code WeatherRenderState}
 * exposes its {@code rainPieces}/{@code snowPieces} lists as public,
 * mutable fields, already split by precipitation type by the time
 * {@code buildPrecipitationPieces} returns. Clearing whichever list(s) are
 * being suppressed in a {@code TAIL} injection avoids the private-method
 * problem entirely -- no {@code @Invoker}, no duplicated biome lookup.
 * {@code TAIL} only matches this method's true final {@code return} (not
 * its earlier "rain gradient is zero" early-return), confirmed via
 * {@code javap -c}: harmless, since nothing was built to clear on that path
 * anyway.
 */
@Mixin(WeatherRendering.class)
abstract class WeatherRenderingNoRainMixin {

    @Inject(method = "buildPrecipitationPieces", at = @At("TAIL"))
    private void lazuli$clearSuppressedPieces(World world, int radius, float tickProgress, Vec3d cameraPos,
            WeatherRenderState state, CallbackInfo ci) {
        if (!TweakEngineHandoff.require().isNoRainActive()) {
            return;
        }
        state.rainPieces.clear();
        if (TweakEngineHandoff.require().noRainIncludesSnow()) {
            state.snowPieces.clear();
        }
    }

    @Inject(method = "addParticlesAndSound", at = @At("HEAD"), cancellable = true)
    private void lazuli$cancelSound(ClientWorld world, Camera camera, int ticks, ParticlesMode particlesMode,
            int radius, CallbackInfo ci) {
        if (TweakEngineHandoff.require().isNoRainActive() && TweakEngineHandoff.require().noRainIncludesSound()) {
            ci.cancel();
        }
    }
}
