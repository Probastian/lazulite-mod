package de.lazuli.features.serverbrowser.services;

import de.lazuli.api.serverbrowser.ServerBrowserColumn;
import de.lazuli.api.serverbrowser.ServerBrowserFilterState;
import de.lazuli.api.serverbrowser.ServerBrowserRow;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pure, stateless sort+filter logic over {@code List<ServerBrowserRow>}
 * (spec FR2.3/FR3.6/Public API item 2, Decision 4). Zero
 * {@code net.minecraft.*}/{@code com.codedisaster.steamworks.*} import
 * (NFR1) -- unit-tested on a plain JVM.
 *
 * <p>Usage example:
 * <pre>{@code
 * ServerBrowserTableModel model = new ServerBrowserTableModel();
 * List<ServerBrowserRow> visible = model.apply(rawRows, ServerBrowserColumn.PING, true, ServerBrowserFilterState.DEFAULT);
 * }</pre>
 */
public final class ServerBrowserTableModel {

    /**
     * Sorts {@code rows} by {@code sortColumn}/{@code ascending} and then
     * filters by {@code filter} (FR2.2-FR2.4, FR3.1-FR3.6). Sort is applied
     * before filter is applied, but since filtering never reorders, the
     * result is equivalent either order -- sorting first keeps this method a
     * single readable pipeline.
     *
     * @param rows       the raw, unsorted/unfiltered row list
     * @param sortColumn the column to sort by
     * @param ascending  sort direction
     * @param filter     the filter state to apply
     * @return a new, sorted-then-filtered list; {@code rows} is not mutated
     */
    public List<ServerBrowserRow> apply(List<ServerBrowserRow> rows, ServerBrowserColumn sortColumn,
                                         boolean ascending, ServerBrowserFilterState filter) {
        Comparator<ServerBrowserRow> comparator = comparatorFor(sortColumn);
        if (!ascending) {
            comparator = comparator.reversed();
        }
        return rows.stream()
                .sorted(comparator)
                .filter(row -> matches(row, filter))
                .toList();
    }

    /**
     * @param column the column to sort by
     * @return an ascending {@link Comparator} for {@code column} (FR2.3);
     *         reverse it for descending
     */
    public Comparator<ServerBrowserRow> comparatorFor(ServerBrowserColumn column) {
        return switch (column) {
            case NAME -> Comparator.comparing(row -> row.serverName().toLowerCase(Locale.ROOT));
            case MAP -> Comparator.comparing(row -> mapOrModeString(row).toLowerCase(Locale.ROOT));
            case PLAYERS -> Comparator.comparingInt(ServerBrowserRow::players);
            case PING -> Comparator.comparingInt(ServerBrowserRow::ping);
            case PASSWORD -> Comparator.comparing(ServerBrowserRow::hasPassword);
            case SECURE -> Comparator.comparing(ServerBrowserRow::isSecure);
        };
    }

    /**
     * @param row    the row to test
     * @param filter the current filter state
     * @return whether {@code row} passes every enabled filter, combined via
     *         logical AND (FR3.1-FR3.6)
     */
    public boolean matches(ServerBrowserRow row, ServerBrowserFilterState filter) {
        if (filter.searchText() != null && !filter.searchText().isEmpty()) {
            String needle = filter.searchText().toLowerCase(Locale.ROOT);
            String haystack = row.serverName() == null ? "" : row.serverName().toLowerCase(Locale.ROOT);
            if (!haystack.contains(needle)) {
                return false;
            }
        }
        if (filter.hideFull() && row.players() >= row.maxPlayers()) {
            return false;
        }
        if (filter.hidePasswordProtected() && row.hasPassword()) {
            return false;
        }
        if (filter.maxPing() > 0 && row.ping() > filter.maxPing()) {
            return false;
        }
        if (filter.hideEmpty() && row.players() == 0) {
            return false;
        }
        return true;
    }

    private static String mapOrModeString(ServerBrowserRow row) {
        return row.map() == null || row.map().isEmpty() ? "" : row.map();
    }
}
