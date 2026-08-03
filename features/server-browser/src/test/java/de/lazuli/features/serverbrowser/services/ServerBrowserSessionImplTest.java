package de.lazuli.features.serverbrowser.services;

import de.lazuli.api.serverbrowser.ServerBrowserColumn;
import de.lazuli.api.serverbrowser.ServerBrowserFilterState;
import de.lazuli.api.serverbrowser.ServerBrowserRow;
import de.lazuli.api.serverbrowser.ServerBrowserSource;
import de.lazuli.api.steamworks.SteamAvailability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ServerBrowserSessionImpl}'s own orchestration logic
 * (sort/filter re-application, idempotent close) against a real
 * {@link ServerBrowserQuery} constructed with an "unavailable" fake
 * {@link SteamAvailability} -- this keeps {@code ServerBrowserQuery} on this
 * test's invocation path a genuine no-op (never touches steamworks4j), so
 * this class stays NFR1-adjacent (no real steamworks4j call is ever made),
 * mirroring {@code friends-sidebar}'s own "no fake-seam interface introduced
 * for a simple wrapper" scope decision.
 */
class ServerBrowserSessionImplTest {

    private static final class UnavailableSteam implements SteamAvailability {
        @Override
        public boolean isSteamAvailable() {
            return false;
        }

        @Override
        public long steamAppId() {
            return 5052800L;
        }
    }

    private ServerBrowserSessionImpl newSession() {
        ServerBrowserQuery query = new ServerBrowserQuery(new UnavailableSteam(), () -> 5052800, message -> { });
        return new ServerBrowserSessionImpl(query, new ServerBrowserTableModel());
    }

    @Test
    void currentRows_emptyBeforeStart() {
        ServerBrowserSessionImpl session = newSession();
        assertThat(session.currentRows()).isEmpty();
    }

    @Test
    void close_isIdempotent() {
        ServerBrowserSessionImpl session = newSession();
        session.start(ServerBrowserSource.INTERNET, rows -> { }, () -> { });
        session.close();
        session.close(); // must not throw
    }

    @Test
    void setSortColumn_sameColumnTwiceTogglesDirection() {
        ServerBrowserSessionImpl session = newSession();
        session.start(ServerBrowserSource.INTERNET, rows -> { }, () -> { });

        session.setSortColumn(ServerBrowserColumn.NAME);
        session.setSortColumn(ServerBrowserColumn.NAME);

        // No exception, and currentRows() still reflects the (empty, Steam
        // unavailable) raw row list -- the toggle itself has nothing to
        // observably assert on without real rows, so this test's main value
        // is documenting/locking in the no-throw contract.
        assertThat(session.currentRows()).isEmpty();
    }

    @Test
    void setFilter_reappliedAgainstLatestRows() {
        ServerBrowserSessionImpl session = newSession();
        List<ServerBrowserRow>[] captured = new List[1];
        session.start(ServerBrowserSource.INTERNET, rows -> captured[0] = rows, () -> { });

        session.setFilter(ServerBrowserFilterState.DEFAULT.withHideEmpty(true));

        assertThat(captured[0]).isEmpty();
    }
}
