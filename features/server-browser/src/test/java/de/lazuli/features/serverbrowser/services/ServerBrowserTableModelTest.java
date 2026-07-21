package de.lazuli.features.serverbrowser.services;

import de.lazuli.api.serverbrowser.ServerBrowserColumn;
import de.lazuli.api.serverbrowser.ServerBrowserFilterState;
import de.lazuli.api.serverbrowser.ServerBrowserRow;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServerBrowserTableModelTest {

    private final ServerBrowserTableModel model = new ServerBrowserTableModel();

    private static ServerBrowserRow row(String name, String map, int players, int maxPlayers, int ping,
                                         boolean password, boolean secure) {
        return new ServerBrowserRow(name, map, players, maxPlayers, ping, password, secure, name + ":25565", true);
    }

    // FR2.3 - comparatorFor, each column, both directions

    @Test
    void comparatorForName_sortsCaseInsensitiveAscending() {
        List<ServerBrowserRow> rows = List.of(row("zeta", "", 0, 10, 1, false, false), row("Alpha", "", 0, 10, 1, false, false));
        List<ServerBrowserRow> sorted = rows.stream().sorted(model.comparatorFor(ServerBrowserColumn.NAME)).toList();
        assertThat(sorted).extracting(ServerBrowserRow::serverName).containsExactly("Alpha", "zeta");
    }

    @Test
    void comparatorForName_reversedIsDescending() {
        List<ServerBrowserRow> rows = List.of(row("Alpha", "", 0, 10, 1, false, false), row("zeta", "", 0, 10, 1, false, false));
        Comparator<ServerBrowserRow> descending = model.comparatorFor(ServerBrowserColumn.NAME).reversed();
        List<ServerBrowserRow> sorted = rows.stream().sorted(descending).toList();
        assertThat(sorted).extracting(ServerBrowserRow::serverName).containsExactly("zeta", "Alpha");
    }

    @Test
    void comparatorForMap_usesGetMapOrGameDescriptionFallback() {
        ServerBrowserRow a = new ServerBrowserRow("a", "aztec", 0, 10, 1, false, false, "a:1", true);
        ServerBrowserRow b = new ServerBrowserRow("b", "zulu", 0, 10, 1, false, false, "b:1", true);
        List<ServerBrowserRow> sorted = List.of(b, a).stream().sorted(model.comparatorFor(ServerBrowserColumn.MAP)).toList();
        assertThat(sorted).extracting(ServerBrowserRow::map).containsExactly("aztec", "zulu");
    }

    @Test
    void comparatorForPlayers_ascendingByPlayerCount() {
        List<ServerBrowserRow> rows = List.of(row("a", "", 8, 10, 1, false, false), row("b", "", 2, 10, 1, false, false));
        List<ServerBrowserRow> sorted = rows.stream().sorted(model.comparatorFor(ServerBrowserColumn.PLAYERS)).toList();
        assertThat(sorted).extracting(ServerBrowserRow::players).containsExactly(2, 8);
    }

    @Test
    void comparatorForPing_ascendingByPing() {
        List<ServerBrowserRow> rows = List.of(row("a", "", 0, 10, 90, false, false), row("b", "", 0, 10, 10, false, false));
        List<ServerBrowserRow> sorted = rows.stream().sorted(model.comparatorFor(ServerBrowserColumn.PING)).toList();
        assertThat(sorted).extracting(ServerBrowserRow::ping).containsExactly(10, 90);
    }

    @Test
    void comparatorForPassword_falseBeforeTrueAscending() {
        List<ServerBrowserRow> rows = List.of(row("a", "", 0, 10, 1, true, false), row("b", "", 0, 10, 1, false, false));
        List<ServerBrowserRow> sorted = rows.stream().sorted(model.comparatorFor(ServerBrowserColumn.PASSWORD)).toList();
        assertThat(sorted).extracting(ServerBrowserRow::hasPassword).containsExactly(false, true);
    }

    @Test
    void comparatorForSecure_falseBeforeTrueAscending() {
        List<ServerBrowserRow> rows = List.of(row("a", "", 0, 10, 1, false, true), row("b", "", 0, 10, 1, false, false));
        List<ServerBrowserRow> sorted = rows.stream().sorted(model.comparatorFor(ServerBrowserColumn.SECURE)).toList();
        assertThat(sorted).extracting(ServerBrowserRow::isSecure).containsExactly(false, true);
    }

    // FR2.4 - default sort is ascending ping

    @Test
    void apply_defaultAscendingPingSort() {
        List<ServerBrowserRow> rows = List.of(row("a", "", 0, 10, 90, false, false), row("b", "", 0, 10, 10, false, false));
        List<ServerBrowserRow> result = model.apply(rows, ServerBrowserColumn.PING, true, ServerBrowserFilterState.DEFAULT);
        assertThat(result).extracting(ServerBrowserRow::serverName).containsExactly("b", "a");
    }

    // FR3.1-FR3.5 - individual filters

    @Test
    void matches_searchTextIsCaseInsensitiveSubstring() {
        ServerBrowserRow row = row("Survival Server", "", 0, 10, 1, false, false);
        assertThat(model.matches(row, ServerBrowserFilterState.DEFAULT.withSearchText("SURVIVAL"))).isTrue();
        assertThat(model.matches(row, ServerBrowserFilterState.DEFAULT.withSearchText("creative"))).isFalse();
    }

    @Test
    void matches_hideFullExcludesRowsAtOrOverCapacity() {
        assertThat(model.matches(row("a", "", 10, 10, 1, false, false), ServerBrowserFilterState.DEFAULT.withHideFull(true))).isFalse();
        assertThat(model.matches(row("a", "", 9, 10, 1, false, false), ServerBrowserFilterState.DEFAULT.withHideFull(true))).isTrue();
    }

    @Test
    void matches_hidePasswordProtectedExcludesPasswordRows() {
        assertThat(model.matches(row("a", "", 0, 10, 1, true, false), ServerBrowserFilterState.DEFAULT.withHidePasswordProtected(true))).isFalse();
        assertThat(model.matches(row("a", "", 0, 10, 1, false, false), ServerBrowserFilterState.DEFAULT.withHidePasswordProtected(true))).isTrue();
    }

    @Test
    void matches_maxPingZeroMeansNoLimit() {
        assertThat(model.matches(row("a", "", 0, 10, 999, false, false), ServerBrowserFilterState.DEFAULT.withMaxPing(0))).isTrue();
    }

    @Test
    void matches_maxPingExcludesRowsOverThreshold() {
        assertThat(model.matches(row("a", "", 0, 10, 150, false, false), ServerBrowserFilterState.DEFAULT.withMaxPing(100))).isFalse();
        assertThat(model.matches(row("a", "", 0, 10, 50, false, false), ServerBrowserFilterState.DEFAULT.withMaxPing(100))).isTrue();
    }

    @Test
    void matches_hideEmptyExcludesZeroPlayerRows() {
        assertThat(model.matches(row("a", "", 0, 10, 1, false, false), ServerBrowserFilterState.DEFAULT.withHideEmpty(true))).isFalse();
        assertThat(model.matches(row("a", "", 1, 10, 1, false, false), ServerBrowserFilterState.DEFAULT.withHideEmpty(true))).isTrue();
    }

    // FR3.6 - combined via AND

    @Test
    void matches_combinesFiltersWithLogicalAnd() {
        // A row that passes hideFull alone and hidePasswordProtected alone,
        // but is excluded once both are combined together with a second row
        // that trips the other filter.
        ServerBrowserRow fullButNoPassword = row("full", "", 10, 10, 1, false, false);
        ServerBrowserRow passwordButNotFull = row("locked", "", 1, 10, 1, true, false);
        ServerBrowserFilterState combined = ServerBrowserFilterState.DEFAULT.withHideFull(true).withHidePasswordProtected(true);

        assertThat(model.matches(fullButNoPassword, combined)).isFalse();
        assertThat(model.matches(passwordButNotFull, combined)).isFalse();
    }

    @Test
    void apply_sortsThenFilters() {
        List<ServerBrowserRow> rows = List.of(
                row("full", "", 10, 10, 5, false, false),
                row("open-slow", "", 1, 10, 200, false, false),
                row("open-fast", "", 1, 10, 20, false, false));
        List<ServerBrowserRow> result = model.apply(rows, ServerBrowserColumn.PING, true,
                ServerBrowserFilterState.DEFAULT.withHideFull(true));
        assertThat(result).extracting(ServerBrowserRow::serverName).containsExactly("open-fast", "open-slow");
    }
}
