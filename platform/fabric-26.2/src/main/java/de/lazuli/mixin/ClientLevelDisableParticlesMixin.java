package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T7 (Disable Particles): cancels vanilla's client-side
 * particle-spawn entry points when the tweak's whitelist/blacklist/all
 * mode excludes the particle type.
 *
 * <p>Targets both public {@code addParticle} overloads on {@code
 * ClientLevel} (confirmed via {@code javap} identical on 26.1 and 26.2).
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelDisableParticlesMixin {

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V",
            at = @At("HEAD"), cancellable = true)
    private void lazuli$cancelParticle1(ParticleOptions options, double x, double y, double z,
            double xSpeed, double ySpeed, double zSpeed, CallbackInfo ci) {
        if (lazuli$shouldCancel(options)) {
            ci.cancel();
        }
    }

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V",
            at = @At("HEAD"), cancellable = true)
    private void lazuli$cancelParticle2(ParticleOptions options, boolean alwaysRender, boolean important,
            double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, CallbackInfo ci) {
        if (lazuli$shouldCancel(options)) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean lazuli$shouldCancel(ParticleOptions options) {
        String id = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType()).toString();
        return !TweakEngineHandoff.require().shouldSpawnParticle(id);
    }
}
