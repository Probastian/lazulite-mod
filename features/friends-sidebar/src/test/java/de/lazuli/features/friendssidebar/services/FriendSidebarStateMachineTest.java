package de.lazuli.features.friendssidebar.services;

import de.lazuli.api.friends.FriendSummary;
import de.lazuli.features.friendssidebar.api.JoinPolicy;

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
        return new FriendSummary(1L, "Friend", 0, 0, inGame, 0L, joinable, joinable ? "127.0.0.1:25565" : null);
    }

    private static FriendSummary friend(String personaName, int personaState, boolean inGame) {
        return new FriendSummary(1L, personaName, personaState, 0, inGame, 0L, false, null);
    }

    @Test
    void clampScrollNoOpWhenZeroDelta() {
        assertThat(stateMachine.clampScroll(3, 0, 20, 10)).isEqualTo(3);
    }

    @Test
    void clampScrollClampsAtTop() {
        assertThat(stateMachine.clampScroll(0, -5, 20, 10)).isEqualTo(0);
    }

    @Test
    void clampScrollClampsAtBottom() {
        assertThat(stateMachine.clampScroll(8, 5, 20, 10)).isEqualTo(10);
    }

    @Test
    void clampScrollAlwaysZeroWhenEverythingFits() {
        assertThat(stateMachine.clampScroll(0, 3, 5, 10)).isZero();
        assertThat(stateMachine.clampScroll(0, 3, 0, 10)).isZero();
    }

    @Test
    void statusColorArgbGroupsOnlineLookingToTradeLookingToPlayAsSameGreen() {
        int online = stateMachine.statusColorArgb(1);
        int lookingToTrade = stateMachine.statusColorArgb(5);
        int lookingToPlay = stateMachine.statusColorArgb(6);

        assertThat(online).isEqualTo(lookingToTrade).isEqualTo(lookingToPlay);
        assertThat(online >>> 24).isEqualTo(0xFF);
    }

    @Test
    void statusColorArgbGroupsOfflineAndInvisibleAsSameGrey() {
        int offline = stateMachine.statusColorArgb(0);
        int invisible = stateMachine.statusColorArgb(7);

        assertThat(offline).isEqualTo(invisible);
        assertThat(offline >>> 24).isEqualTo(0xFF);
    }

    @Test
    void statusColorArgbEveryOrdinalIsFullAlpha() {
        for (int personaState = 0; personaState <= 7; personaState++) {
            assertThat(stateMachine.statusColorArgb(personaState) >>> 24).isEqualTo(0xFF);
        }
    }

    @Test
    void statusLabelEveryOrdinalIsNonNull() {
        for (int personaState = 0; personaState <= 7; personaState++) {
            assertThat(stateMachine.statusLabel(personaState)).isNotNull().isNotBlank();
        }
    }

    @Test
    void statusColorArgbRecolorPinsOnlineLookingToTradeLookingToPlayToBlue() {
        assertThat(stateMachine.statusColorArgb(1)).isEqualTo(0xFF4A90D9);
        assertThat(stateMachine.statusColorArgb(5)).isEqualTo(0xFF4A90D9);
        assertThat(stateMachine.statusColorArgb(6)).isEqualTo(0xFF4A90D9);
    }

    @Test
    void statusColorArgbRecolorPinsAwaySnoozeToGreyedBlue() {
        assertThat(stateMachine.statusColorArgb(3)).isEqualTo(0XFF6184AA);
        assertThat(stateMachine.statusColorArgb(4)).isEqualTo(0XFF6184AA);
    }

    @Test
    void statusColorArgbBusyAndOfflineUnchangedByRecolor() {
        assertThat(stateMachine.statusColorArgb(2)).isEqualTo(0XFF3F5E7E);
        assertThat(stateMachine.statusColorArgb(0)).isEqualTo(0xFF898989);
        assertThat(stateMachine.statusColorArgb(7)).isEqualTo(0xFF898989);
    }

    @Test
    void statusColorArgbInGameOverloadReturnsGreenRegardlessOfPersonaState() {
        for (int personaState = 0; personaState <= 7; personaState++) {
            assertThat(stateMachine.statusColorArgb(personaState, true)).isEqualTo(0xFF5BA32F);
        }
    }

    @Test
    void statusColorArgbInGameOverloadDelegatesToBareOverloadWhenNotInGame() {
        for (int personaState = 0; personaState <= 7; personaState++) {
            assertThat(stateMachine.statusColorArgb(personaState, false))
                    .isEqualTo(stateMachine.statusColorArgb(personaState));
        }
    }

    @Test
    void statusSortRankInGameOverloadReturnsZeroRegardlessOfPersonaState() {
        for (int personaState = 0; personaState <= 7; personaState++) {
            assertThat(stateMachine.statusSortRank(personaState, true)).isZero();
        }
    }

    @Test
    void statusSortRankInGameOverloadMatchesExplicitMappingWhenNotInGame() {
        assertThat(stateMachine.statusSortRank(1, false)).isEqualTo(1);
        assertThat(stateMachine.statusSortRank(5, false)).isEqualTo(1);
        assertThat(stateMachine.statusSortRank(6, false)).isEqualTo(1);
        assertThat(stateMachine.statusSortRank(3, false)).isEqualTo(2);
        assertThat(stateMachine.statusSortRank(4, false)).isEqualTo(2);
        assertThat(stateMachine.statusSortRank(2, false)).isEqualTo(3);
        assertThat(stateMachine.statusSortRank(0, false)).isEqualTo(4);
        assertThat(stateMachine.statusSortRank(7, false)).isEqualTo(4);
    }

    @Test
    void statusLabelInGameOverloadReturnsInGameRegardlessOfPersonaState() {
        for (int personaState = 0; personaState <= 7; personaState++) {
            assertThat(stateMachine.statusLabel(personaState, true)).isEqualTo("In Game");
        }
    }

    @Test
    void statusLabelInGameOverloadDelegatesToBareOverloadWhenNotInGame() {
        for (int personaState = 0; personaState <= 7; personaState++) {
            assertThat(stateMachine.statusLabel(personaState, false))
                    .isEqualTo(stateMachine.statusLabel(personaState));
        }
    }

    @Test
    void sortForDisplaySortsInGameFriendAheadOfNonInGameOnlineFriend() {
        FriendSummary inGameButBusy = friend("Zed", 2, true);
        FriendSummary onlineNotInGame = friend("Alice", 1, false);
        FriendSummary offlineNotInGame = friend("Bob", 0, false);

        java.util.List<FriendSummary> sorted = stateMachine.sortForDisplay(
                java.util.List.of(offlineNotInGame, onlineNotInGame, inGameButBusy));

        assertThat(sorted).containsExactly(inGameButBusy, onlineNotInGame, offlineNotInGame);
    }

    @Test
    void nextJoinPolicyCyclesInFixedOrderIncludingWrapAround() {
        assertThat(stateMachine.nextJoinPolicy(JoinPolicy.NOBODY)).isEqualTo(JoinPolicy.FRIENDS);
        assertThat(stateMachine.nextJoinPolicy(JoinPolicy.FRIENDS)).isEqualTo(JoinPolicy.EVERYONE);
        assertThat(stateMachine.nextJoinPolicy(JoinPolicy.EVERYONE)).isEqualTo(JoinPolicy.NOBODY);
    }
}
