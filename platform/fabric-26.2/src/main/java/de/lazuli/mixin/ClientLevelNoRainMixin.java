package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.multiplayer.ClientLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T13 (No Rain): 26.2-only sound-suppression half, sibling to
 * {@link WeatherEffectRendererNoRainMixin} (visual suppression).
 *
 * <p><strong>Confirmed via {@code javap} against this module's own resolved
 * merged Minecraft jar -- a real 26.1-vs-26.2 divergence, not just a
 * rename</strong> (see {@code .claude/context/minecraft.md}): on 26.2, the
 * rain-splash-particle + ambient-sound logic 1.21.11's {@code
 * WeatherRendering.addParticlesAndSound}/26.1's {@code
 * WeatherEffectRenderer.tickRainParticles} implement was moved off the
 * renderer entirely, onto a new no-arg {@code ClientLevel.tickWeatherEffects()}
 * method (confirmed via {@code javap -c} bytecode trace: same rain-gradient
 * early-return, same per-column {@code getPrecipitationAt(BlockPos) == RAIN}
 * gate, same {@code SoundEvents.WEATHER_RAIN}/{@code WEATHER_RAIN_ABOVE}
 * playback via {@code playLocalSound}). This method is a "tick"-shaped
 * no-op-when-not-raining entry point, not a query -- cancelling its {@code
 * HEAD} when {@code includeSound} is active suppresses the same rain sound/
 * splash-particle behavior the other two platforms suppress via their own
 * renderer-owned method.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelNoRainMixin {

    @Inject(method = "tickWeatherEffects", at = @At("HEAD"), cancellable = true)
    private void lazuli$cancelSound(CallbackInfo ci) {
        if (TweakEngineHandoff.require().isNoRainActive() && TweakEngineHandoff.require().noRainIncludesSound()) {
            ci.cancel();
        }
    }
}
