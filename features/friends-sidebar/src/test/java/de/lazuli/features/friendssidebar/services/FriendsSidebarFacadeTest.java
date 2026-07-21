package de.lazuli.features.friendssidebar.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain-JVM coverage of {@link FriendsSidebarFacade}'s v1.2 three-way
 * visibility surface (NFR6/FR6.2/FR6.3, implementation plan Decision 16) --
 * {@code isEnabled()}/{@code isSteamAvailable()} are independently settable/
 * gettable and each default to {@code true}, and {@code steamUnavailableMessage()}
 * returns the same fixed, non-empty constant regardless of state.
 */
class FriendsSidebarFacadeTest {

    private final FriendsSidebarFacade facade =
            new FriendsSidebarFacade(new NoopFriendsService(), new FriendSidebarStateMachine());

    @Test
    void isEnabledAndIsSteamAvailableDefaultToTrue() {
        assertThat(facade.isEnabled()).isTrue();
        assertThat(facade.isSteamAvailable()).isTrue();
    }

    @Test
    void isEnabledFalseRegardlessOfSteamAvailability() {
        // FR6.2 outcome 1: config.enabled() == false -> fully hidden,
        // regardless of Steam's own availability.
        facade.setEnabled(false);

        facade.setSteamAvailable(true);
        assertThat(facade.isEnabled()).isFalse();

        facade.setSteamAvailable(false);
        assertThat(facade.isEnabled()).isFalse();
    }

    @Test
    void enabledButSteamUnavailableIsIndependentlyObservable() {
        // FR6.2 outcome 2: config.enabled() == true, Steam unavailable ->
        // status state.
        facade.setEnabled(true);
        facade.setSteamAvailable(false);

        assertThat(facade.isEnabled()).isTrue();
        assertThat(facade.isSteamAvailable()).isFalse();
    }

    @Test
    void enabledAndSteamAvailableIsContentState() {
        // FR6.2 outcome 3: content state, unchanged from today.
        facade.setEnabled(true);
        facade.setSteamAvailable(true);

        assertThat(facade.isEnabled()).isTrue();
        assertThat(facade.isSteamAvailable()).isTrue();
    }

    @Test
    void steamUnavailableMessageIsFixedAndNonEmptyRegardlessOfState() {
        String messageWhenAvailable = facade.steamUnavailableMessage();
        assertThat(messageWhenAvailable).isNotBlank();

        facade.setSteamAvailable(false);
        assertThat(facade.steamUnavailableMessage()).isEqualTo(messageWhenAvailable);

        facade.setEnabled(false);
        assertThat(facade.steamUnavailableMessage()).isEqualTo(messageWhenAvailable);
    }
}
