package de.lazuli.api.cloudsync;

/**
 * Stable, Minecraft-free abstraction over this device's bookmarked-server
 * state, consumed by a platform Version Adapter (a
 * {@code ScreenEvents.AFTER_INIT}-based bookmark-toggle widget injected into
 * the vanilla Multiplayer server-list screen) and implemented by
 * {@code features/steam-cloud-sync}'s own {@code BookmarkedServersService}.
 *
 * <p>This is the same {@code MainMenuHook} shape (a small {@code api}-layer
 * hook interface, per {@code ui-guidelines.md} Pattern 1) applied in the
 * opposite direction: here the Feature implements the interface and the
 * Platform Version Adapter holds/calls it, rather than Platform implementing
 * it for a Feature to call.
 *
 * <p>Usage example (from a platform Version Adapter holding a
 * constructor-injected {@code BookmarkSyncHook}):
 * <pre>{@code
 * BookmarkSyncHook hook = ...; // supplied by the platform composition root
 * boolean bookmarked = hook.isBookmarked(serverAddress);
 * toggleWidget.setBookmarked(bookmarked);
 * toggleWidget.onClick(() -> hook.toggleBookmark(serverAddress, serverName));
 * }</pre>
 */
public interface BookmarkSyncHook {

    /**
     * @param address the server address (host:port) as shown on the
     *                Multiplayer screen
     * @return {@code true} if this address is currently bookmarked
     */
    boolean isBookmarked(String address);

    /**
     * Adds a bookmark for {@code address} if none exists yet, or removes the
     * existing bookmark for it if one does -- a plain on/off toggle.
     *
     * @param address the server address to toggle
     * @param label   a human-readable label to use if a new bookmark is
     *                created (e.g. the server's configured name); ignored if
     *                an existing bookmark is being removed
     */
    void toggleBookmark(String address, String label);
}
