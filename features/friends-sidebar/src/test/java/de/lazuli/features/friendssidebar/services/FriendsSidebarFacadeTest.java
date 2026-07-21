package de.lazuli.features.friendssidebar.services;

import de.lazuli.features.friendssidebar.api.JoinPolicy;

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
            new FriendsSidebarFacade(new NoopFriendsService(), new FriendSidebarStateMachine(),
                    JoinPolicy.FRIENDS, policy -> { });

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
    void selectJoinPolicySetsValueDirectlyAndInvokesWriter() {
        // v1.4 amendment: the DropdownWidget-backed replacement for
        // cycleJoinPolicy()'s click-to-cycle interaction -- sets the value
        // directly (not via the fixed cycle order) and republishes it
        // through the same persistence/bridge-republish callback.
        JoinPolicy[] written = new JoinPolicy[1];
        FriendsSidebarFacade facadeWithCapture = new FriendsSidebarFacade(new NoopFriendsService(),
                new FriendSidebarStateMachine(), JoinPolicy.FRIENDS, policy -> written[0] = policy);

        facadeWithCapture.selectJoinPolicy(JoinPolicy.EVERYONE);

        assertThat(facadeWithCapture.joinPolicy()).isEqualTo(JoinPolicy.EVERYONE);
        assertThat(written[0]).isEqualTo(JoinPolicy.EVERYONE);
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
