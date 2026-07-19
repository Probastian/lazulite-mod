package de.lazuli.friends;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Uploads a friend's raw RGBA avatar bytes (as delivered by
 * {@code FriendsService.avatarRgba(long)}) to a real GPU texture the first
 * time it becomes available, keyed by {@code steamId64} (implementation plan
 * Decision 5). Confirmed against this repo's own real, resolved 1.21.11
 * (Yarn) jar: {@code NativeImage.setColorArgb(x, y, argb)} is the exact
 * per-pixel upload call this side has -- **not** the ABGR-only
 * {@code setPixelABGR}/{@code setPixel} 26.x's Mojang-mapped
 * {@code com.mojang.blaze3d.platform.NativeImage} uses, and the texture
 * class here is {@code NativeImageBackedTexture} rather than
 * {@code DynamicTexture} (26.x's {@code DynamicTexture} name is instead an
 * *interface* {@code NativeImageBackedTexture} implements on this side) --
 * see {@code .claude/context/minecraft.md}'s Known Cross-Version API
 * Differences table.
 *
 * <p>Usage example (from {@code FriendSidebarWidget}'s own render code):
 * <pre>{@code
 * Identifier textureId = avatarTextureCache.getOrUpload(steamId64, () -> facade.avatarRgba(steamId64));
 * if (textureId != null) {
 *     context.drawTexture(textureId, x, y, 0, 0, 16, 16, 16, 16);
 * }
 * }</pre>
 */
public final class AvatarTextureCache {

    // Steam's "large" avatar (SteamFriends.getLargeFriendAvatar) is always
    // delivered at this fixed resolution.
    static final int AVATAR_SIZE = 184;
    public static final int SIZE = AVATAR_SIZE;

    private final Map<Long, Identifier> uploaded = new HashMap<>();
    private final Map<Long, byte[]> uploadedSource = new HashMap<>();
    private final Consumer<String> warnLogger;

    public AvatarTextureCache(Consumer<String> warnLogger) {
        this.warnLogger = warnLogger;
    }

    /**
     * @param steamId64 the friend whose avatar this is
     * @param rgba      the raw RGBA byte array (4 bytes/pixel, row-major,
     *                  {@link #AVATAR_SIZE}x{@link #AVATAR_SIZE}), or
     *                  {@code null}/of the wrong length if not available yet
     * @return the uploaded texture's {@link Identifier}, or {@code null} if
     *         no avatar has been successfully uploaded for this friend yet
     */
    public Identifier getOrUpload(long steamId64, byte[] rgba) {
        Identifier existing = uploaded.get(steamId64);
        // FriendsService replaces its avatarsById entry with a new array
        // instance whenever a fresher avatar arrives (e.g. Steam's
        // placeholder is swapped for the real image after
        // onAvatarImageLoaded fires) -- re-upload rather than keeping the
        // first (possibly placeholder) texture forever.
        if (existing != null && uploadedSource.get(steamId64) == rgba) {
            return existing;
        }
        if (rgba == null || rgba.length != AVATAR_SIZE * AVATAR_SIZE * 4) {
            return existing;
        }
        try {
            NativeImage image = new NativeImage(AVATAR_SIZE, AVATAR_SIZE, false);
            for (int y = 0; y < AVATAR_SIZE; y++) {
                for (int x = 0; x < AVATAR_SIZE; x++) {
                    int i = (y * AVATAR_SIZE + x) * 4;
                    int r = rgba[i] & 0xFF;
                    int g = rgba[i + 1] & 0xFF;
                    int b = rgba[i + 2] & 0xFF;
                    int a = rgba[i + 3] & 0xFF;
                    int argb = (a << 24) | (r << 16) | (g << 8) | b;
                    image.setColorArgb(x, y, argb);
                }
            }
            NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "lazuli friend avatar " + steamId64, image);
            texture.upload();
            Identifier id = existing != null
                    ? existing
                    : Identifier.of("lazuli", "friend_avatar_" + Long.toHexString(steamId64));
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
            uploaded.put(steamId64, id);
            uploadedSource.put(steamId64, rgba);
            return id;
        } catch (RuntimeException e) {
            warnLogger.accept("Failed to upload avatar texture for friend " + steamId64 + ": " + e.getMessage());
            return existing;
        }
    }
}
