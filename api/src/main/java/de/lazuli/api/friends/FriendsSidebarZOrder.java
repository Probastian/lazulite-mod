package de.lazuli.api.friends;

/**
 * Centralizes the manual draw order of the Friends Sidebar overlay's layers.
 * {@code FriendSidebarWidget} (both its closed-row {@code renderNow(...)}
 * pass and its open-state {@code renderDropdownOverlay(...)} pass) and
 * {@code FriendContextMenuWidget} are drawn manually (via
 * {@code renderNow(...)}/{@code renderDropdownOverlay(...)} methods invoked
 * from {@code FabricFriendsSidebarInjector}'s single after-render hook)
 * rather than through the normal per-screen widget draw order, specifically
 * so this enum's ordinal order -- not incidental widget-list position -- is
 * what determines what's on top. Each platform's injector must draw layers
 * in ascending {@link #ordinal()} order (lowest first/furthest back).
 *
 * <p>{@link #DROPDOWN_OVERLAY} and {@link #CONTEXT_MENU} are both the same
 * topmost-overlay tier, above {@link #SIDEBAR} -- their relative declaration
 * order carries no priority meaning between the two of them, because the
 * join-policy dropdown's open state and the row-click context menu are
 * mutually exclusive at runtime (opening one closes/never opens the other),
 * so they are never both visible in the same frame (v1.5 amendment,
 * features/friends-sidebar/specification.md Compatibility delta).
 */
public enum FriendsSidebarZOrder {
    /** The sidebar itself: background, own-profile row, friend rows. */
    SIDEBAR,
    /**
     * The join-policy dropdown's open-state option-row overlay -- same
     * topmost tier as {@link #CONTEXT_MENU}, see class Javadoc.
     */
    DROPDOWN_OVERLAY,
    /**
     * The row-click context menu -- same topmost tier as
     * {@link #DROPDOWN_OVERLAY}, see class Javadoc.
     */
    CONTEXT_MENU
}
