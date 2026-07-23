package de.lazuli.mainmenu;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.WorldIcon;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Shared world/server icon texture cache (post-launch-fixes spec FX2/FX4.1)
 * -- {@code fabric-1.21.11} (Yarn-mapped) port of the {@code fabric-26.1}/
 * {@code fabric-26.2} class of the same name, built on this version's own
 * {@link WorldIcon} (the Yarn-mapped equivalent of 26.x's
 * {@code FaviconTexture} -- same shape: {@link WorldIcon#forWorld}/
 * {@link WorldIcon#forServer} return an instance already pointing at a
 * built-in "missing" sprite until {@link WorldIcon#load(NativeImage)} is
 * called, so the fallback (FX2.3/FX4.1) is free).
 *
 * <p>Disk/byte-array reads happen off the render thread (implementation plan
 * Risk R3), mirroring {@code WorldsPanel.reload()}'s own existing
 * {@code thenAcceptAsync(..., MinecraftClient.getInstance())} pattern.
 */
public final class IconTextureCache {

    private final Map<String, WorldIcon> textures = new HashMap<>();
    private final Map<String, String> loadedVersionKeys = new HashMap<>();
    private final Consumer<String> warnLogger;

    public IconTextureCache(Consumer<String> warnLogger) {
        this.warnLogger = warnLogger;
    }

    /**
     * @param levelId  the world's stable save-folder id (cache key)
     * @param iconPath the world's {@code icon.png} path (may not exist)
     * @return the cached/fallback identifier to draw this frame
     */
    public Identifier forWorld(String levelId, Path iconPath) {
        WorldIcon texture = textures.computeIfAbsent(levelId,
                id -> WorldIcon.forWorld(MinecraftClient.getInstance().getTextureManager(), id));
        maybeLoad(levelId, iconPath.toString(), texture, () -> Files.exists(iconPath) ? Files.readAllBytes(iconPath) : null);
        return texture.getTextureId();
    }

    /**
     * @param rowId        a stable per-row cache key (e.g. {@code "saved:" + index})
     * @param faviconBytes the server's raw favicon PNG bytes, or {@code null}/empty if not pinged yet
     * @return the cached/fallback identifier to draw this frame
     */
    public Identifier forServer(String rowId, byte[] faviconBytes) {
        WorldIcon texture = textures.computeIfAbsent(rowId,
                id -> WorldIcon.forServer(MinecraftClient.getInstance().getTextureManager(), id));
        String versionKey = faviconBytes == null ? "none" : (faviconBytes.length + "@" + System.identityHashCode(faviconBytes));
        maybeLoad(rowId, versionKey, texture, () -> faviconBytes);
        return texture.getTextureId();
    }

    private void maybeLoad(String key, String versionKey, WorldIcon texture, Callable<byte[]> byteSupplier) {
        if (versionKey.equals(loadedVersionKeys.get(key))) {
            return;
        }
        loadedVersionKeys.put(key, versionKey);
        CompletableFuture.supplyAsync(() -> {
            try {
                return byteSupplier.call();
            } catch (Exception e) {
                return null;
            }
        }).thenAcceptAsync(bytes -> {
            if (bytes == null || bytes.length == 0) {
                return;
            }
            try (NativeImage image = NativeImage.read(bytes)) {
                texture.load(image);
            } catch (IOException e) {
                warnLogger.accept("Failed to decode icon for " + key + ": " + e.getMessage());
            }
        }, MinecraftClient.getInstance());
    }

    /** Invalidates every cached icon (FX2.4, e.g. {@code WorldsPanel.reload()}) so stale textures are re-derived. */
    public void invalidateAll() {
        textures.values().forEach(WorldIcon::close);
        textures.clear();
        loadedVersionKeys.clear();
    }
}
