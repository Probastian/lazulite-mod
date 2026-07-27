package de.lazuli.mixin;

import de.lazuli.tweaks.TweakEngineHandoff;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tweaks spec T7 (Disable Particles): cancels vanilla's client-side
 * particle-spawn entry points when the tweak's whitelist/blacklist/all
 * mode excludes the particle type.
 *
 * <p>Targets both public {@code addParticleClient} overloads on {@code
 * ClientWorld} (confirmed via {@code javap} to be the client-settings-
 * respecting and "force" entry points every particle spawn funnels
 * through).
 */
@Mixin(ClientWorld.class)
abstract class ClientWorldDisableParticlesMixin {

    @Inject(method = "addParticleClient(Lnet/minecraft/particle/ParticleEffect;DDDDDD)V",
            at = @At("HEAD"), cancellable = true)
    private void lazuli$cancelParticle1(ParticleEffect parameters, double x, double y, double z,
            double velocityX, double velocityY, double velocityZ, CallbackInfo ci) {
        if (lazuli$shouldCancel(parameters)) {
            ci.cancel();
        }
    }

    @Inject(method = "addParticleClient(Lnet/minecraft/particle/ParticleEffect;ZZDDDDDD)V",
            at = @At("HEAD"), cancellable = true)
    private void lazuli$cancelParticle2(ParticleEffect parameters, boolean alwaysSpawn, boolean important,
            double x, double y, double z, double velocityX, double velocityY, double velocityZ, CallbackInfo ci) {
        if (lazuli$shouldCancel(parameters)) {
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean lazuli$shouldCancel(ParticleEffect parameters) {
        String id = Registries.PARTICLE_TYPE.getId(parameters.getType()).toString();
        return !TweakEngineHandoff.require().shouldSpawnParticle(id);
    }
}
