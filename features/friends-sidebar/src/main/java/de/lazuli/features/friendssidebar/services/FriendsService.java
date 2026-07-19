package de.lazuli.features.friendssidebar.services;

import de.lazuli.api.friends.FriendSummary;
import de.lazuli.features.friendssidebar.api.FriendsSidebarConfig;

import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamFriends;
import com.codedisaster.steamworks.SteamFriendsCallback;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamUser;
import com.codedisaster.steamworks.SteamUserCallback;
import com.codedisaster.steamworks.SteamUtils;
import com.codedisaster.steamworks.SteamUtilsCallback;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The <strong>sole</strong> class in this feature importing
 * {@code com.codedisaster.steamworks.*} (implementation plan, Files to
 * Create/services -- mirrors {@code features/steam-cloud-sync}'s own
 * "two seams only" discipline). Owns the {@link SteamFriends}/
 * {@link SteamUtils} instances, the {@link SteamFriendsCallback} registration
 * (FR1.5), the rate-limited refresh sweep (FR1.4), and the four
 * {@code FriendActionListener} methods (FR3.1-FR3.4, Decision 6).
 *
 * <p>Constructed only when {@code SteamAvailability.isSteamAvailable()} is
 * {@code true} and the feature's own config is enabled (FR0.2/FR0.3) -- the
 * platform composition root is responsible for choosing between this class
 * and {@link NoopFriendsService}.
 *
 * <p>Usage example (from a platform composition root):
 * <pre>{@code
 * FriendsDataSource dataSource = new FriendsService(config, LazuliMod.LOGGER::warn);
 * ClientTickEvents.END_CLIENT_TICK.register(client -> dataSource.tick());
 * }</pre>
 */
public final class FriendsService implements FriendsDataSource {

    private final FriendsSidebarConfig config;
    private final Consumer<String> warnLogger;

    private final SteamFriends steamFriends;
    private final SteamUtils steamUtils;
    private final SteamUser steamUser;

    private final Map<Long, FriendSummary> friendsByIdSnapshot = new HashMap<>();
    private final Map<Long, byte[]> avatarsById = new HashMap<>();
    private final Map<Long, String> richPresenceById = new HashMap<>();
    private final Set<Long> dirtyAvatars = new HashSet<>();

    /** Valve's own documented Rich Presence key for a human-readable status string (FR1.7). */
    private static final String RICH_PRESENCE_STATUS_KEY = "status";

    private long lastRefreshAtMillis = -1L;

    /**
     * @param config     this feature's own config (used for
     *                   {@link FriendsSidebarConfig#refreshIntervalSeconds()})
     * @param warnLogger sink for non-fatal warnings (e.g. overlay
     *                   unavailable, a single friend's state failing to
     *                   resolve) -- never throws (NFR2)
     */
    public FriendsService(FriendsSidebarConfig config, Consumer<String> warnLogger) {
        this.config = config;
        this.warnLogger = warnLogger;
        this.steamFriends = new SteamFriends(new Callback());
        this.steamUtils = new SteamUtils(new UtilsCallback());
        this.steamUser = new SteamUser(new UserCallback());
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
        int count = steamFriends.getFriendCount(SteamFriends.FriendFlags.Immediate);
        for (int i = 0; i < count; i++) {
            SteamID friend = steamFriends.getFriendByIndex(i, SteamFriends.FriendFlags.Immediate);
            if (friend == null) {
                continue;
            }
            try {
                resolveFriend(friend);
            } catch (RuntimeException e) {
                warnLogger.accept("Failed to resolve a Steam friend's state: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        // Re-resolve any avatar the callback marked dirty since the last sweep.
        Set<Long> dirtySnapshot = new HashSet<>(dirtyAvatars);
        dirtyAvatars.clear();
        for (long steamId64 : dirtySnapshot) {
            SteamID friend = SteamID.createFromNativeHandle(steamId64);
            resolveAvatar(friend);
        }
    }

    private void resolveFriend(SteamID friend) {
        long steamId64 = SteamID.getNativeHandle(friend);
        String personaName = steamFriends.getFriendPersonaName(friend);
        int personaState = steamFriends.getFriendPersonaState(friend).ordinal();

        SteamFriends.FriendGameInfo gameInfo = new SteamFriends.FriendGameInfo();
        boolean inGame = steamFriends.getFriendGamePlayed(friend, gameInfo);
        String connectHint = null;
        if (inGame && gameInfo.getGamePort() != 0) {
            connectHint = ipToString(gameInfo.getGameIP()) + ":" + (gameInfo.getGamePort() & 0xFFFF);
        }
        // v1 never treats a friend as actionably "joinable" -- Invite/Join
        // stay disabled placeholders regardless (FR2.6, FR3.3, FR3.4); this
        // field is still populated so a future extension is a small change.
        boolean joinable = inGame && connectHint != null;

        int avatarHandle = steamFriends.getLargeFriendAvatar(friend);

        friendsByIdSnapshot.put(steamId64,
                new FriendSummary(steamId64, personaName, personaState, avatarHandle, inGame, joinable, connectHint));

        if (avatarHandle != 0 && !avatarsById.containsKey(steamId64)) {
            resolveAvatar(friend);
        }

        // FR1.7: Rich Presence values are cached locally by Steam and only
        // refresh when explicitly requested -- request every sweep, then
        // read the "status" key; empty/unset falls back to the plain
        // persona-state word at render time (FR4.8), never rendered blank.
        try {
            steamFriends.requestFriendRichPresence(friend);
            String richPresence = steamFriends.getFriendRichPresence(friend, RICH_PRESENCE_STATUS_KEY);
            if (richPresence != null && !richPresence.isEmpty()) {
                richPresenceById.put(steamId64, richPresence);
            } else {
                richPresenceById.remove(steamId64);
            }
        } catch (RuntimeException e) {
            warnLogger.accept("Failed to resolve Rich Presence for a Steam friend: " + e.getMessage());
        }
    }

    @Override
    public Optional<FriendSummary> localProfile() {
        try {
            SteamID self = steamUser.getSteamID();
            if (self == null) {
                return Optional.empty();
            }
            long steamId64 = SteamID.getNativeHandle(self);
            String personaName = steamFriends.getPersonaName();
            int personaState = steamFriends.getPersonaState().ordinal();
            int avatarHandle = steamFriends.getLargeFriendAvatar(self);
            if (avatarHandle != 0 && !avatarsById.containsKey(steamId64)) {
                resolveAvatar(self);
            }
            // The own-profile row never uses inGame/joinable/connectHint
            // (FR2.8 disables Invite/Join for this row unconditionally) and
            // never resolves Rich Presence (FR1.6 -- friend-relative only).
            return Optional.of(new FriendSummary(steamId64, personaName, personaState, avatarHandle, false, false, null));
        } catch (RuntimeException e) {
            warnLogger.accept("Failed to resolve local Steam profile: " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> richPresenceStatus(long steamId64) {
        return Optional.ofNullable(richPresenceById.get(steamId64));
    }

    private void resolveAvatar(SteamID friend) {
        long steamId64 = SteamID.getNativeHandle(friend);
        int avatarHandle = steamFriends.getLargeFriendAvatar(friend);
        if (avatarHandle == 0) {
            return;
        }
        int[] size = new int[2];
        if (!steamUtils.getImageSize(avatarHandle, size) || size[0] <= 0 || size[1] <= 0) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(size[0] * size[1] * 4);
        try {
            if (steamUtils.getImageRGBA(avatarHandle, buffer)) {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                avatarsById.put(steamId64, bytes);
            }
        } catch (SteamException e) {
            warnLogger.accept("Failed to decode avatar image for a Steam friend: " + e.getMessage());
        }
    }

    private static String ipToString(int ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    @Override
    public List<FriendSummary> currentFriends() {
        return Collections.unmodifiableList(new ArrayList<>(friendsByIdSnapshot.values()));
    }

    @Override
    public Optional<byte[]> avatarRgba(long steamId64) {
        return Optional.ofNullable(avatarsById.get(steamId64));
    }

    @Override
    public void onOpenChat(long steamId64) {
        activateOverlay(steamId64, SteamFriends.OverlayToUserDialog.Chat, "chat");
    }

    @Override
    public void onShowProfile(long steamId64) {
        activateOverlay(steamId64, SteamFriends.OverlayToUserDialog.SteamID, "profile");
    }

    private void activateOverlay(long steamId64, SteamFriends.OverlayToUserDialog dialog, String label) {
        try {
            if (!steamUtils.isOverlayEnabled()) {
                warnLogger.accept("Steam overlay is not enabled; cannot open " + label + " for " + steamId64 + ".");
                return;
            }
            steamFriends.activateGameOverlayToUser(dialog, SteamID.createFromNativeHandle(steamId64));
        } catch (RuntimeException e) {
            warnLogger.accept("Failed to open Steam overlay " + label + " for " + steamId64 + ": " + e.getMessage());
        }
    }

    @Override
    public void onInvite(long steamId64) {
        // FR3.3: v1 disabled placeholder -- unreachable from the UI (Decision 6).
        // No connect-string design exists yet; never calls inviteUserToGame.
    }

    @Override
    public void onJoin(long steamId64) {
        // FR3.4: v1 disabled placeholder -- unreachable from the UI (Decision 6).
        // No real join flow exists yet.
    }

    /**
     * Receives Valve's asynchronous friend-state/avatar-delivery callbacks
     * (FR1.5). Never throws back into steamworks4j's own callback pump.
     */
    private final class Callback implements SteamFriendsCallback {
        @Override
        public void onPersonaStateChange(SteamID steamID, SteamFriends.PersonaChange change) {
            // Marked dirty implicitly -- the next tick()'s refresh sweep
            // re-resolves every friend's full state regardless (FR1.4 is
            // already cheap enough not to need finer-grained dirty tracking
            // for persona state itself; only avatar re-fetch is tracked
            // explicitly below, since it is the one call re-issued only on
            // demand rather than every sweep).
        }

        @Override
        public void onAvatarImageLoaded(SteamID steamID, int image, int width, int height) {
            dirtyAvatars.add(SteamID.getNativeHandle(steamID));
        }
    }

    /**
     * Required by steamworks4j's {@link SteamUtils} constructor; this
     * feature has no use for {@code SteamUtilsCallback}'s own events.
     */
    private static final class UtilsCallback implements SteamUtilsCallback {
    }

    /**
     * Required by steamworks4j's {@link SteamUser} constructor (FR1.6);
     * every declared method on {@link SteamUserCallback} is a default
     * method (javap-confirmed against this repo's own resolved
     * {@code steamworks4j-1.10.0.jar}), so this feature has no use for any
     * of its events beyond the zero-arg {@code getSteamID()} query itself.
     */
    private static final class UserCallback implements SteamUserCallback {
    }
}
