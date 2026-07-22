package de.lazuli.friends;

import de.lazuli.api.friends.FriendSummary;
import de.lazuli.api.worldhosting.FriendHostingStatusReader;
import de.lazuli.api.worldhosting.WorldInviteSender;
import de.lazuli.api.worldhosting.WorldJoinRequester;
import de.lazuli.features.friendssidebar.services.FriendsDataSource;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;
import de.lazuli.services.ui.ToastService;

import net.minecraft.client.gui.Click;
import net.minecraft.client.input.MouseInput;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression test for {@code specification-own-profile-ingame-status.md}
 * FR-CM1/FR-CM2: the own-profile row's context menu ({@code isOwnProfile =
 * true}) must only ever enable/act on "Show profile" (index 1), even when
 * the {@link WorldInviteSender}/{@link FriendHostingStatusReader}
 * collaborators are stubbed maximally permissive (as if the local player
 * were hosting and every friend were hosting too) -- proving the
 * {@code isOwnProfile} short-circuit in {@link FriendContextMenuWidget#isEnabled(int)}
 * truly overrides those gates rather than merely happening to agree with
 * them today.
 */
class FriendContextMenuWidgetTest {

    private static final FriendSummary FRIEND = new FriendSummary(
            76561198000000000L, "Steve", 1, 0, false, 0L, false, null);

    private FriendContextMenuWidget newOwnProfileMenu(FriendsSidebarFacade facade, WorldJoinRequester worldJoinRequester,
            FriendHostingStatusReader hostingStatusReader, WorldInviteSender worldInviteSender, ToastService toastService) {
        return new FriendContextMenuWidget(0, 0, FRIEND, facade, () -> { },
                true, worldJoinRequester, hostingStatusReader, worldInviteSender, toastService);
    }

    @Test
    void isEnabled_onlyShowProfile_whenOwnProfile_evenWithMaximallyPermissiveStubs() {
        FriendsSidebarFacade facade = mock(FriendsSidebarFacade.class);
        FriendsDataSource dataSource = mock(FriendsDataSource.class);
        when(facade.actions()).thenReturn(dataSource);
        WorldInviteSender worldInviteSender = mock(WorldInviteSender.class);
        FriendHostingStatusReader hostingStatusReader = mock(FriendHostingStatusReader.class);
        WorldJoinRequester worldJoinRequester = mock(WorldJoinRequester.class);
        ToastService toastService = mock(ToastService.class);
        when(worldInviteSender.isHosting()).thenReturn(true);
        when(hostingStatusReader.isFriendHosting(Mockito.anyLong())).thenReturn(true);

        FriendContextMenuWidget menu = newOwnProfileMenu(facade, worldJoinRequester, hostingStatusReader, worldInviteSender, toastService);

        // isEnabled(int) is private; exercise it indirectly via mouseClicked
        // at each row's coordinates (FR-CM1 Acceptance Criteria item 1/2) --
        // a click only has an observable effect when isEnabled(index) is true.
        for (int index = 0; index < 4; index++) {
            double clickY = index * 16 + 4;
            menu.mouseClicked(clickEvent(4, clickY), false);
        }

        FriendsDataSource actions = facade.actions();
        verify(actions, times(1)).onShowProfile(FRIEND.steamId64());
        verify(actions, never()).onOpenChat(Mockito.anyLong());
        verify(worldInviteSender, never()).inviteFriend(Mockito.anyLong());
        verify(worldJoinRequester, never()).joinHostedWorld(Mockito.anyLong());
    }

    @Test
    void mouseClicked_onOwnProfileMenu_onlyShowProfileRowInvokesAnything() {
        FriendsSidebarFacade facade = mock(FriendsSidebarFacade.class);
        FriendsDataSource dataSource = mock(FriendsDataSource.class);
        when(facade.actions()).thenReturn(dataSource);
        WorldInviteSender worldInviteSender = mock(WorldInviteSender.class);
        FriendHostingStatusReader hostingStatusReader = mock(FriendHostingStatusReader.class);
        WorldJoinRequester worldJoinRequester = mock(WorldJoinRequester.class);
        ToastService toastService = mock(ToastService.class);
        when(worldInviteSender.isHosting()).thenReturn(true);
        when(hostingStatusReader.isFriendHosting(Mockito.anyLong())).thenReturn(true);

        FriendContextMenuWidget menu = newOwnProfileMenu(facade, worldJoinRequester, hostingStatusReader, worldInviteSender, toastService);

        boolean rowZeroConsumed = menu.mouseClicked(clickEvent(4, 4), false);
        boolean rowTwoConsumed = menu.mouseClicked(clickEvent(4, 2 * 16 + 4), false);
        boolean rowThreeConsumed = menu.mouseClicked(clickEvent(4, 3 * 16 + 4), false);
        boolean rowOneConsumed = menu.mouseClicked(clickEvent(4, 16 + 4), false);

        assertThat(rowZeroConsumed).isTrue();
        assertThat(rowTwoConsumed).isTrue();
        assertThat(rowThreeConsumed).isTrue();
        assertThat(rowOneConsumed).isTrue();

        verify(facade.actions(), times(1)).onShowProfile(FRIEND.steamId64());
        verify(facade.actions(), never()).onOpenChat(Mockito.anyLong());
        verifyNoInteractions(worldInviteSender);
        verifyNoInteractions(worldJoinRequester);
    }

    private static Click clickEvent(double x, double y) {
        return new Click(x, y, new MouseInput(0, 0));
    }
}
