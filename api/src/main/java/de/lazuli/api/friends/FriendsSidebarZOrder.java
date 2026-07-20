package de.lazuli.api.friends;

/**
 * Centralizes the manual draw order of the Friends Sidebar overlay's layers.
 * Both {@code FriendSidebarWidget} and {@code FriendContextMenuWidget} are
 * drawn manually (via a {@code renderNow(...)} method invoked from
 * {@code FabricFriendsSidebarInjector}'s single after-render hook) rather
 * than through the normal per-screen widget draw order, specifically so this
 * enum's ordinal order -- not incidental widget-list position -- is what
 * determines what's on top. Each platform's injector must draw layers in
 * ascending {@link #ordinal()} order (lowest first/furthest back).
 */
public enum FriendsSidebarZOrder {
    /** The sidebar itself: background, own-profile row, friend rows. */
    SIDEBAR,
    /** The row-click context menu -- always drawn above the sidebar. */
    CONTEXT_MENU
}
