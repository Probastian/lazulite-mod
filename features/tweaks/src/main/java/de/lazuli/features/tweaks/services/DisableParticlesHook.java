package de.lazuli.features.tweaks.services;

/** Minecraft-agnostic hook interface for T7 Disable Particles (spec Requirements T7). */
public interface DisableParticlesHook {

    /**
     * @param particleTypeId the particle type's registry id
     * @return {@code true} if this particle type is allowed to spawn/render
     */
    boolean shouldSpawnParticle(String particleTypeId);
}
