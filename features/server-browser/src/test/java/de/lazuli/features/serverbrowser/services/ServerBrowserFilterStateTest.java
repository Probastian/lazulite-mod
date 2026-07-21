package de.lazuli.features.serverbrowser.services;

import de.lazuli.api.serverbrowser.ServerBrowserFilterState;
import de.lazuli.api.serverbrowser.ServerBrowserRow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerBrowserFilterStateTest {

    private final ServerBrowserTableModel model = new ServerBrowserTableModel();

    @Test
    void defaultFilter_matchesAnyRow() {
        ServerBrowserRow full = new ServerBrowserRow("Full Server", "map", 10, 10, 999, true, true, "a:1", true);
        ServerBrowserRow empty = new ServerBrowserRow("Empty Server", "map", 0, 10, 1, false, false, "b:1", true);

        assertThat(model.matches(full, ServerBrowserFilterState.DEFAULT)).isTrue();
        assertThat(model.matches(empty, ServerBrowserFilterState.DEFAULT)).isTrue();
    }

    @Test
    void defaultFilter_hasNoSearchTextNoTogglesNoPingLimit() {
        assertThat(ServerBrowserFilterState.DEFAULT.searchText()).isEmpty();
        assertThat(ServerBrowserFilterState.DEFAULT.hideFull()).isFalse();
        assertThat(ServerBrowserFilterState.DEFAULT.hidePasswordProtected()).isFalse();
        assertThat(ServerBrowserFilterState.DEFAULT.maxPing()).isZero();
        assertThat(ServerBrowserFilterState.DEFAULT.hideEmpty()).isFalse();
    }
}
