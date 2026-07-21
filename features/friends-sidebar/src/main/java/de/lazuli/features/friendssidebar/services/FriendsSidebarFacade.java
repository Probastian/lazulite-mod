package de.lazuli.features.friendssidebar.services;

import de.lazuli.api.friends.FriendSidebarHook;
import de.lazuli.api.friends.FriendSummary;
import de.lazuli.features.friendssidebar.api.JoinPolicy;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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
 * FriendsSidebarFacade facade = new FriendsSidebarFacade(dataSource, new FriendSidebarStateMachine(),
 *         config.joinPolicy(), onJoinPolicyChanged);
 * facade.setEnabled(config.enabled());
 * facade.setSteamAvailable(steamworksService.isSteamAvailable());
 * ClientTickEvents.END_CLIENT_TICK.register(client -> {
 *     dataSource.tick();
 *     facade.refresh();
 * });
 * new FabricFriendsSidebarInjector(facade);
 * }</pre>
 */
public final class FriendsSidebarFacade implements FriendSidebarHook {

    /**
     * The single, generic status message shown by the sidebar's status
     * state (FR6.1/FR6.5) whenever the feature is enabled but Steam itself
     * is unavailable -- deliberately not sourced from any per-cause
     * {@code SteamworksService} failure detail (FR6.1's collapse-to-one-
     * message justification).
     */
    public static final String STEAM_UNAVAILABLE_MESSAGE =
            "Steam not available - make sure Steam is running and this game was "
            + "either launched through Steam or has a valid steam_appid.txt";

    private final FriendsDataSource dataSource;
    private final FriendSidebarStateMachine stateMachine;
    private final Consumer<JoinPolicy> joinPolicyWriter;

    private volatile List<FriendSummary> friends = List.of();
    private volatile Optional<FriendSummary> localProfile = Optional.empty();
    private volatile boolean enabled = true;
    private volatile boolean steamAvailable = true;
    private volatile JoinPolicy joinPolicy;

    /**
     * @param initialJoinPolicy the persisted policy value at construction
     *                          time (v1.3 amendment, Decision 3)
     * @param joinPolicyWriter  invoked with the new value every time
     *                          {@link #cycleJoinPolicy()} is called -- the
     *                          composition-root persistence + bridge-republish
     *                          callback (Decision 5)
     */
    public FriendsSidebarFacade(FriendsDataSource dataSource, FriendSidebarStateMachine stateMachine,
            JoinPolicy initialJoinPolicy, Consumer<JoinPolicy> joinPolicyWriter) {
        this.dataSource = dataSource;
        this.stateMachine = stateMachine;
        this.joinPolicy = initialJoinPolicy;
        this.joinPolicyWriter = joinPolicyWriter;
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
     * Independent of {@link #setEnabled(boolean)}/{@link #isEnabled()}
     * (Decision 16) -- reflects {@code SteamworksService.isSteamAvailable()}
     * as of the last time the composition root called this setter, defaults
     * to {@code true} so a caller that never invokes this (e.g. a future
     * test harness) degrades to today's content-rendering behavior rather
     * than silently entering the status state.
     *
     * @param available whether Steam itself is currently available
     */
    public void setSteamAvailable(boolean available) {
        this.steamAvailable = available;
    }

    /**
     * @return {@code true} unless the composition root has told this facade
     *         Steam is unavailable ({@link #setSteamAvailable(boolean)}) --
     *         when {@code false} (and {@link #isEnabled()} is {@code true}),
     *         a Version Adapter should render the status state (FR6.2-FR6.7)
     *         instead of friend-list content
     */
    public boolean isSteamAvailable() {
        return steamAvailable;
    }

    /**
     * @return the fixed, generic "Steam unavailable" status message (FR6.1)
     *         a Version Adapter should render in the status state
     */
    public String steamUnavailableMessage() {
        return STEAM_UNAVAILABLE_MESSAGE;
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

    /**
     * @return the currently-active "who can join" policy (v1.3 amendment,
     *         FR7.5) -- reflects the standing config value regardless of
     *         whether a world is currently hosted
     */
    public JoinPolicy joinPolicy() {
        return joinPolicy;
    }

    /**
     * Advances {@link #joinPolicy()} to the next value in the fixed cycle
     * (FR7.3) and invokes the persistence/bridge-republish callback with the
     * new value. Never itself starts/stops/affects a hosting session (FR7.7).
     */
    public void cycleJoinPolicy() {
        JoinPolicy next = stateMachine.nextJoinPolicy(joinPolicy);
        joinPolicy = next;
        joinPolicyWriter.accept(next);
    }

    /**
     * Sets {@link #joinPolicy()} directly to {@code policy} and invokes the
     * persistence/bridge-republish callback with the new value (v1.4
     * amendment, Public API item 11) -- the {@code DropdownWidget}-backed
     * replacement for {@link #cycleJoinPolicy()}'s click-to-cycle
     * interaction. Never itself starts/stops/affects a hosting session
     * (FR7.7).
     */
    public void selectJoinPolicy(JoinPolicy policy) {
        joinPolicy = policy;
        joinPolicyWriter.accept(policy);
    }
}
