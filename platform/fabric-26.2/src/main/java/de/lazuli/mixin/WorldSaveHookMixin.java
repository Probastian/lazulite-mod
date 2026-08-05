package de.lazuli.mixin;

import de.lazuli.WorldSaveHookHolder;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

/**
 * The FR-T.1 mid-session upload trigger (Cloud Sync Status UI spec): fires
 * whenever the integrated server finishes an on-disk save, so a Cloud
 * upload can be queued without waiting for world-unload/disconnect.
 *
 * <p>Target confirmed via {@code javap} against this module's resolved
 * 26.2 jar: {@code net.minecraft.server.MinecraftServer#saveEverything(boolean, boolean, boolean)Z}
 * (public). <strong>Not</strong> overridden on
 * {@code net.minecraft.client.server.IntegratedServer} in this Minecraft
 * version -- unlike {@code fabric-1.21.11}'s Yarn-mapped
 * {@code net.minecraft.server.integrated.IntegratedServer#saveAll}, which
 * *is* declared directly on the integrated-server subclass there (see
 * {@code .claude/context/minecraft.md}'s "Known Cross-Version API
 * Differences" table). This mixin therefore targets {@link MinecraftServer}
 * itself rather than {@link IntegratedServer}, guarded by an
 * {@code instanceof} check so it is a no-op for a dedicated server.
 */
@Mixin(MinecraftServer.class)
public abstract class WorldSaveHookMixin {

    @Inject(method = "saveEverything", at = @At("RETURN"))
    private void lazuli$onSaveEverything(
            boolean suppressLogs, boolean flush, boolean forced, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        MinecraftServer self = (MinecraftServer) (Object) this;
        if (!(self instanceof IntegratedServer)) {
            return;
        }
        try {
            Path worldFolder = self.getWorldPath(LevelResource.ROOT).normalize();
            String worldSlug = worldFolder.getFileName().toString();
            String displayName = self.getWorldData().getLevelName();
            WorldSaveHookHolder.onWorldSaved(worldSlug, worldFolder, displayName);
        } catch (RuntimeException e) {
            // Best-effort only -- a failure to resolve world info here must
            // never break the vanilla save path itself.
        }
    }
}
