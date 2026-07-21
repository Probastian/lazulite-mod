package de.lazuli.api.serverbrowser;

import java.util.List;
import java.util.function.Consumer;

/**
 * The per-open-{@code ServerBrowserScreen} live handle a platform Version
 * Adapter drives: starts/refreshes/closes the underlying Steam matchmaking
 * query and re-applies sort/filter on every mutation (spec Decision 2).
 * Implemented feature-side by {@code ServerBrowserSessionImpl}; a fresh
 * instance is obtained per screen-open via {@link ServerBrowserSessionFactory#newSession()}
 * -- never shared/reused across opens (FR1.4/FR3.7).
 *
 * <p>Usage example (from {@code ServerBrowserScreen}):
 * <pre>{@code
 * ServerBrowserSession session = sessionFactory.newSession();
 * session.start(ServerBrowserSource.INTERNET, rows -> listWidget.replaceRows(rows), () -> refreshing = false);
 * session.setSortColumn(ServerBrowserColumn.PING);
 * session.setFilter(ServerBrowserFilterState.DEFAULT.withHideFull(true));
 * // ... on Screen.onClose()/removed():
 * session.close();
 * }</pre>
 */
public interface ServerBrowserSession {

    /**
     * Issues one Steam matchmaking list request for {@code source}
     * (FR1.1/FR1.5/FR1.6). Releases any previously in-flight request first
     * (FR1.4). A no-op (never touches steamworks4j) if Steam is unavailable.
     *
     * @param source          the source to query (FR1.6)
     * @param onRowsChanged   invoked with the current, already-sorted/filtered
     *                        row list every time the raw row list mutates
     * @param onRefreshComplete invoked when {@code refreshComplete} fires (FR1.2)
     */
    void start(ServerBrowserSource source, Consumer<List<ServerBrowserRow>> onRowsChanged, Runnable onRefreshComplete);

    /** Re-issues a refresh against the existing request (FR1.3) -- never a new request. */
    void refresh();

    /** @return whether the underlying request is currently refreshing (FR1.3) */
    boolean isRefreshing();

    /**
     * Sets the sort column (FR2.2); calling with the same column as the
     * current one toggles ascending/descending, calling with a different
     * column replaces the sort and resets to ascending.
     *
     * @param column the column to sort by
     */
    void setSortColumn(ServerBrowserColumn column);

    /**
     * Replaces the current filter state (FR3.1-FR3.6) and re-applies
     * sort+filter over the latest raw row list.
     *
     * @param filter the new filter state
     */
    void setFilter(ServerBrowserFilterState filter);

    /** @return the current, already-sorted-and-filtered row list */
    List<ServerBrowserRow> currentRows();

    /** @return the column the row list is currently sorted by (FR2.2/FR2.4) */
    ServerBrowserColumn sortColumn();

    /** @return {@code true} if {@link #sortColumn()} is sorted ascending, {@code false} if descending */
    boolean sortAscending();

    /**
     * Releases the underlying Steam matchmaking request, if any (FR1.4).
     * Idempotent -- safe to call multiple times, including when no request
     * was ever issued (Steam unavailable).
     */
    void close();
}
