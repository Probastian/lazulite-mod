package de.lazuli.mainmenu;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Shared world/server icon texture cache (post-launch-fixes spec FX2/FX4.1),
 * built directly on vanilla's own {@link FaviconTexture} -- the exact
 * mechanism {@code WorldSelectionList.WorldListEntry}/
 * {@code ServerSelectionList.OnlineServerEntry} already use for the same
 * per-world/per-server icon identity plus "missing" fallback texture
 * (confirmed via {@code javap} against this module's own resolved 26.2 jar:
 * {@link FaviconTexture#forWorld}/{@link FaviconTexture#forServer} return an
 * instance whose {@link FaviconTexture#textureLocation()} already points at a
 * built-in "missing" sprite until {@link FaviconTexture#upload(NativeImage)}
 * is called -- so the fallback (FX2.3/FX4.1) is free, not something this
 * cache has to draw itself).
 *
 * <p>Disk/byte-array reads happen off the render thread (implementation plan
 * Risk R3): {@link #forWorld}/{@link #forServer} kick off an async load the
 * first time a key is seen (or the underlying bytes identity changes) and
 * return the already-fallback-backed identifier immediately; once bytes are
 * decoded, the actual GL upload is hopped back onto the render thread via
 * {@code thenAcceptAsync(..., Minecraft.getInstance())} -- the same pattern
 * {@code WorldsPanel.reload()} already uses for its own level-summary load.
 */
public final class IconTextureCache {

    private final Map<String, FaviconTexture> textures = new HashMap<>();
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
        FaviconTexture texture = textures.computeIfAbsent(levelId,
                id -> FaviconTexture.forWorld(Minecraft.getInstance().getTextureManager(), id));
        maybeLoad(levelId, iconPath.toString(), texture, () -> Files.exists(iconPath) ? Files.readAllBytes(iconPath) : null);
        return texture.textureLocation();
    }

    /**
     * @param rowId     a stable per-row cache key (e.g. {@code "saved:" + index})
     * @param faviconBytes the server's raw favicon PNG bytes, or {@code null}/empty if not pinged yet
     * @return the cached/fallback identifier to draw this frame
     */
    public Identifier forServer(String rowId, byte[] faviconBytes) {
        FaviconTexture texture = textures.computeIfAbsent(rowId,
                id -> FaviconTexture.forServer(Minecraft.getInstance().getTextureManager(), id));
        String versionKey = faviconBytes == null ? "none" : (faviconBytes.length + "@" + System.identityHashCode(faviconBytes));
        maybeLoad(rowId, versionKey, texture, () -> faviconBytes);
        return texture.textureLocation();
    }

    private void maybeLoad(String key, String versionKey, FaviconTexture texture, Callable<byte[]> byteSupplier) {
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
                texture.upload(image);
            } catch (IOException e) {
                warnLogger.accept("Failed to decode icon for " + key + ": " + e.getMessage());
            }
        }, Minecraft.getInstance());
    }

    /** Invalidates every cached icon (FX2.4, e.g. {@code WorldsPanel.reload()}) so stale textures are re-derived. */
    public void invalidateAll() {
        textures.values().forEach(FaviconTexture::close);
        textures.clear();
        loadedVersionKeys.clear();
    }
}
