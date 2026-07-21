package de.lazuli.api.serverbrowser;

/**
 * The combined, session-lifetime-only filter state (FR3.1-FR3.7) driving
 * {@code ServerBrowserTableModel.matches(ServerBrowserRow, ServerBrowserFilterState)}.
 * All non-default fields combine with logical AND.
 *
 * <p>Usage example:
 * <pre>{@code
 * ServerBrowserFilterState filter = ServerBrowserFilterState.DEFAULT
 *         .withSearchText("survival")
 *         .withHideFull(true);
 * session.setFilter(filter);
 * }</pre>
 *
 * @param searchText           case-insensitive substring match against
 *                             {@code serverName} (FR3.1); empty means no
 *                             filtering by name
 * @param hideFull             hide rows where {@code players >= maxPlayers} (FR3.2)
 * @param hidePasswordProtected hide rows where {@code hasPassword} is {@code true} (FR3.3)
 * @param maxPing              hide rows whose {@code ping} exceeds this value;
 *                             {@code 0} means no limit (FR3.4)
 * @param hideEmpty            hide rows where {@code players == 0} (FR3.5)
 */
public record ServerBrowserFilterState(String searchText, boolean hideFull, boolean hidePasswordProtected,
                                        int maxPing, boolean hideEmpty) {

    /** The no-op filter: empty search, no toggles, no ping limit -- every screen open resets to this (FR3.7). */
    public static final ServerBrowserFilterState DEFAULT = new ServerBrowserFilterState("", false, false, 0, false);

    public ServerBrowserFilterState withSearchText(String searchText) {
        return new ServerBrowserFilterState(searchText, hideFull, hidePasswordProtected, maxPing, hideEmpty);
    }

    public ServerBrowserFilterState withHideFull(boolean hideFull) {
        return new ServerBrowserFilterState(searchText, hideFull, hidePasswordProtected, maxPing, hideEmpty);
    }

    public ServerBrowserFilterState withHidePasswordProtected(boolean hidePasswordProtected) {
        return new ServerBrowserFilterState(searchText, hideFull, hidePasswordProtected, maxPing, hideEmpty);
    }

    public ServerBrowserFilterState withMaxPing(int maxPing) {
        return new ServerBrowserFilterState(searchText, hideFull, hidePasswordProtected, maxPing, hideEmpty);
    }

    public ServerBrowserFilterState withHideEmpty(boolean hideEmpty) {
        return new ServerBrowserFilterState(searchText, hideFull, hidePasswordProtected, maxPing, hideEmpty);
    }
}
