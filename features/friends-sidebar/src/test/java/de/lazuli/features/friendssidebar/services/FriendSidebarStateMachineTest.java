package de.lazuli.features.friendssidebar.services;

import de.lazuli.api.friends.FriendSummary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FriendSidebarStateMachineTest {

    private final FriendSidebarStateMachine stateMachine = new FriendSidebarStateMachine();

    @Test
    void collapsedWhenMouseOutsideSidebarBounds() {
        boolean expanded = stateMachine.isExpanded(1000, 1000, 0, 0, 32, 200);
        assertThat(expanded).isFalse();
    }

    @Test
    void expandsWholeSidebarWhenMouseInsideBounds() {
        boolean expanded = stateMachine.isExpanded(10, 50, 0, 0, 32, 200);
        assertThat(expanded).isTrue();
    }

    @Test
    void boundaryEdgesAreInclusiveOnTopLeftExclusiveOnBottomRight() {
        assertThat(stateMachine.isExpanded(0, 0, 0, 0, 32, 200)).isTrue();
        assertThat(stateMachine.isExpanded(32, 200, 0, 0, 32, 200)).isFalse();
    }

    @Test
    void openChatAndShowProfileAreAlwaysEnabled() {
        FriendSummary offlineNotJoinable = friend(false, false);
        FriendSummary inGameJoinable = friend(true, true);

        assertThat(stateMachine.isOpenChatEnabled(offlineNotJoinable)).isTrue();
        assertThat(stateMachine.isOpenChatEnabled(inGameJoinable)).isTrue();
        assertThat(stateMachine.isShowProfileEnabled(offlineNotJoinable)).isTrue();
        assertThat(stateMachine.isShowProfileEnabled(inGameJoinable)).isTrue();
    }

    @Test
    void inviteAndJoinAreAlwaysDisabledRegardlessOfFriendState() {
        FriendSummary offlineNotJoinable = friend(false, false);
        FriendSummary inGameJoinable = friend(true, true);

        assertThat(stateMachine.isInviteEnabled(offlineNotJoinable)).isFalse();
        assertThat(stateMachine.isInviteEnabled(inGameJoinable)).isFalse();
        assertThat(stateMachine.isJoinEnabled(offlineNotJoinable)).isFalse();
        assertThat(stateMachine.isJoinEnabled(inGameJoinable)).isFalse();
    }

    private static FriendSummary friend(boolean inGame, boolean joinable) {
        return new FriendSummary(1L, "Friend", 0, 0, inGame, joinable, joinable ? "127.0.0.1:25565" : null);
    }
}
