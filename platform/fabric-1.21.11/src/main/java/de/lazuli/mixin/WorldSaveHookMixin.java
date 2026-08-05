package de.lazuli.mixin;

import de.lazuli.WorldSaveHookHolder;

import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

/**
 * 1.21.11 (Yarn-mapped) variant of the FR-T.1 mid-session upload trigger
 * (Cloud Sync Status UI spec): fires whenever the integrated server
 * finishes an on-disk save.
 *
 * <p>Target confirmed via {@code javap} against this module's resolved Yarn
 * jar: {@code net.minecraft.server.integrated.IntegratedServer#saveAll(boolean, boolean, boolean)Z}
 * (public) -- Yarn's mapped name for vanilla's {@code saveEverything}, and
 * (unlike {@code fabric-26.1}/{@code fabric-26.2}'s official mappings)
 * declared/overridden directly on {@code IntegratedServer} itself in this
 * version, not only on its {@code MinecraftServer} superclass -- see
 * {@code .claude/context/minecraft.md}'s "Known Cross-Version API
 * Differences" table.
 */
@Mixin(IntegratedServer.class)
public abstract class WorldSaveHookMixin {

    @Inject(method = "saveAll", at = @At("RETURN"))
    private void lazuli$onSaveAll(
            boolean suppressLogs, boolean flush, boolean forced, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        IntegratedServer self = (IntegratedServer) (Object) this;
        try {
            Path worldFolder = self.getSavePath(WorldSavePath.ROOT).normalize();
            String worldSlug = worldFolder.getFileName().toString();
            String displayName = self.getSaveProperties().getLevelName();
            WorldSaveHookHolder.onWorldSaved(worldSlug, worldFolder, displayName);
        } catch (RuntimeException e) {
            // Best-effort only -- a failure to resolve world info here must
            // never break the vanilla save path itself.
        }
    }
}
