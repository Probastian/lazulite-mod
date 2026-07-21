package de.lazuli.api.serverbrowser;

/**
 * The six sortable columns of the Server Browser table (FR2.1/FR2.2), in
 * display order.
 *
 * <p>Usage example (header-click handler):
 * <pre>{@code
 * session.setSortColumn(ServerBrowserColumn.PING); // toggles direction on repeat clicks
 * }</pre>
 */
public enum ServerBrowserColumn {
    NAME,
    MAP,
    PLAYERS,
    PING,
    PASSWORD,
    SECURE
}
