package de.lazuli.features.friendssidebar.services;

import de.lazuli.api.friends.FriendSidebarHook;
import de.lazuli.api.friends.FriendSummary;

import java.util.List;
import java.util.Optional;

/**
 * Implements {@link FriendSidebarHook} and {@code FriendActionListener}
 * together as the one object a platform composition root hands to its own
 * Version Adapters (implementation plan, Files to Create/services) -- a thin
 * composition of whichever {@link FriendsDataSource} was constructed
 * ({@link FriendsService} or {@link NoopFriendsService}) plus
 * {@link FriendSidebarStateMachine}.
 *
 * <p>Row/menu clicks are forwarded directly to the underlying
 * {@link FriendsDataSource} (which implements {@code FriendActionListener}
 * itself); {@link #refresh()} re-reads the data source's latest snapshot so
 * platform code always has an up-to-date, already-pulled list to render
 * without needing to poll {@code FriendsDataSource} directly.
 *
 * <p>Usage example (from a platform composition root):
 * <pre>{@code
 * FriendsSidebarFacade facade = new FriendsSidebarFacade(dataSource, new FriendSidebarStateMachine());
 * ClientTickEvents.END_CLIENT_TICK.register(client -> {
 *     dataSource.tick();
 *     facade.refresh();
 * });
 * new FabricFriendsSidebarInjector(facade);
 * }</pre>
 */
public final class FriendsSidebarFacade implements FriendSidebarHook {

    private final FriendsDataSource dataSource;
    private final FriendSidebarStateMachine stateMachine;

    private volatile List<FriendSummary> friends = List.of();
    private volatile Optional<FriendSummary> localProfile = Optional.empty();
    private volatile boolean enabled = true;

    public FriendsSidebarFacade(FriendsDataSource dataSource, FriendSidebarStateMachine stateMachine) {
        this.dataSource = dataSource;
        this.stateMachine = stateMachine;
    }

    /**
     * Re-reads {@link FriendsDataSource#currentFriends()} into this facade's
     * own latest-snapshot field. Call once per client tick, after
     * {@code dataSource.tick()}.
     */
    public void refresh() {
        updateFriends(dataSource.currentFriends());
        localProfile = dataSource.localProfile();
    }

    @Override
    public void updateFriends(List<FriendSummary> friends) {
        this.friends = List.copyOf(friends);
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return {@code true} if the sidebar should currently render at all
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return the latest friend-list snapshot (as of the last {@link #refresh()})
     */
    public List<FriendSummary> friends() {
        return friends;
    }

    /**
     * @param steamId64 the friend to look up an avatar for
     * @return the friend's raw RGBA avatar bytes, if delivered yet
     */
    public Optional<byte[]> avatarRgba(long steamId64) {
        return dataSource.avatarRgba(steamId64);
    }

    /**
     * @return the local player's own pinned-row {@link FriendSummary}
     *         (FR4.4), as of the last {@link #refresh()}
     */
    public Optional<FriendSummary> localProfile() {
        return localProfile;
    }

    /**
     * @param steamId64 the friend to look up Rich Presence for
     * @return the friend's Rich Presence status text (FR1.7/FR4.8), if any
     */
    public Optional<String> richPresenceStatus(long steamId64) {
        return dataSource.richPresenceStatus(steamId64);
    }

    /** @return the underlying action listener (row/menu clicks). */
    public FriendsDataSource actions() {
        return dataSource;
    }

    /** @return the pure hover/expand/menu-availability state machine. */
    public FriendSidebarStateMachine stateMachine() {
        return stateMachine;
    }
}
