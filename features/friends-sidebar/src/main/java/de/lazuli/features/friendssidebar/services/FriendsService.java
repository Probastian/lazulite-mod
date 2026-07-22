package de.lazuli.features.friendssidebar.services;

import de.lazuli.api.friends.FriendSummary;
import de.lazuli.api.worldhosting.WorldInviteSender;
import de.lazuli.features.friendssidebar.api.FriendsSidebarConfig;
import de.lazuli.services.steamworks.SteamFriendsGateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Friends Sidebar's data source, driving the rate-limited refresh sweep
 * (FR1.4), the friend-list snapshot (FR1.1/FR1.2), and the four
 * {@code FriendActionListener} methods (FR3.1-FR3.4).
 *
 * <p>As of Steam World Hosting plan Decision 1 this class no longer owns any
 * {@code SteamFriends}/{@code SteamUtils}/{@code SteamUser} instance itself:
 * every steamworks4j call site is now a plain call into the shared
 * {@link SteamFriendsGateway} (a {@code services}-layer seam consumed by both
 * this feature and {@code features/steam-world-hosting}), so this class -- and
 * this whole feature -- is fully steamworks4j-import-free. This is a
 * dependency-injection-shape change only; every existing public method result
 * is unchanged.
 *
 * <p>Constructed only when {@code SteamAvailability.isSteamAvailable()} is
 * {@code true} and the feature's own config is enabled (FR0.2/FR0.3) -- the
 * platform composition root chooses between this class and
 * {@link NoopFriendsService}.
 *
 * <p>Usage example (from a platform composition root):
 * <pre>{@code
 * SteamFriendsGateway gateway = SteamFriendsGatewayHandoff.require();
 * FriendsDataSource dataSource = new FriendsService(gateway, config, LazuliMod.LOGGER::warn);
 * ClientTickEvents.END_CLIENT_TICK.register(client -> dataSource.tick());
 * }</pre>
 */
public final class FriendsService implements FriendsDataSource {

    private final SteamFriendsGateway gateway;
    private final FriendsSidebarConfig config;
    private final Consumer<String> warnLogger;
    private final WorldInviteSender worldInviteSender;

    private final Map<Long, FriendSummary> friendsByIdSnapshot = new HashMap<>();
    private final Map<Long, String> richPresenceById = new HashMap<>();

    /** Valve's own documented Rich Presence key for a human-readable status string (FR1.7). */
    private static final String RICH_PRESENCE_STATUS_KEY = "status";

    private long lastRefreshAtMillis = -1L;

    /**
     * @param gateway    the shared Steam-friends seam (Decision 1)
     * @param config     this feature's own config (used for
     *                   {@link FriendsSidebarConfig#refreshIntervalSeconds()})
     * @param warnLogger        sink for non-fatal warnings -- never throws (NFR2)
     * @param worldInviteSender Steam World Hosting's invite operation for the
     *                          reused "Invite to game" context-menu slot
     *                          (specification-invite-to-game.md D5); nullable,
     *                          mirroring the existing nullable bridge
     *                          convention -- unused when {@code null}
     */
    public FriendsService(SteamFriendsGateway gateway, FriendsSidebarConfig config, Consumer<String> warnLogger,
            WorldInviteSender worldInviteSender) {
        this.gateway = gateway;
        this.config = config;
        this.warnLogger = warnLogger;
        this.worldInviteSender = worldInviteSender;
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        long intervalMillis = Math.max(1, config.refreshIntervalSeconds()) * 1000L;
        if (lastRefreshAtMillis >= 0 && now - lastRefreshAtMillis < intervalMillis) {
            return;
        }
        lastRefreshAtMillis = now;
        try {
            refresh();
        } catch (RuntimeException e) {
            warnLogger.accept("Friends Sidebar refresh sweep failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void refresh() {
        int count = gateway.friendCount();
        for (int i = 0; i < count; i++) {
            long steamId64 = gateway.friendSteamId64At(i);
            if (steamId64 == 0L) {
                continue;
            }
            try {
                resolveFriend(steamId64);
            } catch (RuntimeException e) {
                warnLogger.accept("Failed to resolve a Steam friend's state: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private void resolveFriend(long steamId64) {
        String personaName = gateway.friendPersonaName(steamId64);
        int personaState = gateway.friendPersonaStateOrdinal(steamId64);

        boolean inGame = gateway.friendInGame(steamId64);
        String connectHint = gateway.friendGameConnectHint(steamId64).orElse(null);
        // v1 never treats a friend as actionably "joinable" via this feature --
        // Invite/Join stay disabled placeholders (FR2.6, FR3.3, FR3.4); the
        // field is still populated so a future extension is a small change.
        boolean joinable = inGame && connectHint != null;

        int avatarHandle = gateway.avatarHandle(steamId64);

        friendsByIdSnapshot.put(steamId64,
                new FriendSummary(steamId64, personaName, personaState, avatarHandle, inGame, joinable, connectHint));

        // FR1.7: Rich Presence values are cached locally by Steam and only
        // refresh when explicitly requested -- request every sweep, then read
        // the "status" key; empty/unset falls back to the plain persona-state
        // word at render time (FR4.8), never rendered blank.
        gateway.requestFriendRichPresence(steamId64);
        Optional<String> richPresence = gateway.friendRichPresenceValue(steamId64, RICH_PRESENCE_STATUS_KEY);
        if (richPresence.isPresent()) {
            richPresenceById.put(steamId64, richPresence.get());
        } else {
            richPresenceById.remove(steamId64);
        }
    }

    @Override
    public Optional<FriendSummary> localProfile() {
        long steamId64 = gateway.localSteamId64();
        if (steamId64 == 0L) {
            return Optional.empty();
        }
        String personaName = gateway.localPersonaName();
        int personaState = gateway.localPersonaStateOrdinal();
        int avatarHandle = gateway.avatarHandle(steamId64);
        // The own-profile row never uses inGame/joinable/connectHint (FR2.8
        // disables Invite/Join for this row unconditionally) and never
        // resolves Rich Presence (FR1.6 -- friend-relative only).
        return Optional.of(new FriendSummary(steamId64, personaName, personaState, avatarHandle, false, false, null));
    }

    @Override
    public Optional<String> richPresenceStatus(long steamId64) {
        return Optional.ofNullable(richPresenceById.get(steamId64));
    }

    @Override
    public List<FriendSummary> currentFriends() {
        return Collections.unmodifiableList(new ArrayList<>(friendsByIdSnapshot.values()));
    }

    @Override
    public Optional<byte[]> avatarRgba(long steamId64) {
        return gateway.avatarRgba(steamId64);
    }

    @Override
    public void onOpenChat(long steamId64) {
        gateway.activateOverlayChat(steamId64);
    }

    @Override
    public void onShowProfile(long steamId64) {
        gateway.activateOverlayProfile(steamId64);
    }

    @Override
    public void onInvite(long steamId64) {
        // FR-INV4/FR-INV5: kept for FriendActionListener completeness and any
        // future non-context-menu caller; the context-menu click path itself
        // bypasses this and calls WorldInviteSender directly (Decision 2 /
        // mirrors "Join game"'s existing onJoin bypass).
        if (worldInviteSender == null || !worldInviteSender.isHosting()) {
            return;
        }
        worldInviteSender.inviteFriend(steamId64);
    }

    @Override
    public void onJoin(long steamId64) {
        // FR3.4: v1 disabled placeholder -- unreachable from the UI via this
        // feature. Steam World Hosting reroutes the reused "Join game" slot to
        // its own WorldJoinRequester at the platform composition root, so this
        // method is never reached via that path either.
    }
}
