package de.lazuli.services.steamworks;

import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamFriends;
import com.codedisaster.steamworks.SteamFriendsCallback;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamUser;
import com.codedisaster.steamworks.SteamUserCallback;
import com.codedisaster.steamworks.SteamUtils;
import com.codedisaster.steamworks.SteamUtilsCallback;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The real, steamworks4j-backed {@link SteamFriendsGateway}: the sole class in
 * {@code services} importing {@code com.codedisaster.steamworks.*} beyond
 * {@link SteamworksService} itself (Steam World Hosting plan Decision 1). Owns
 * the single {@link SteamFriends}/{@link SteamUtils}/{@link SteamUser}
 * instances, the friend-state/avatar callback registrations, and an internal
 * avatar RGBA cache (absorbing what {@code FriendsService} previously owned).
 *
 * <p>Constructed only when {@code SteamAvailability.isSteamAvailable()} is
 * {@code true}; the platform composition root chooses between this and
 * {@link NoopSteamFriendsGateway}. Every method fails closed to a safe default
 * and reports non-fatal problems to the supplied warning sink -- never throws.
 */
public final class SteamworksSteamFriendsGateway implements SteamFriendsGateway {

    private final Consumer<String> warnLogger;

    private final SteamFriends steamFriends;
    private final SteamUtils steamUtils;
    private final SteamUser steamUser;

    private final Map<Long, byte[]> avatarsById = new HashMap<>();
    private final Set<Long> dirtyAvatars = new HashSet<>();

    private volatile BiConsumer<Long, String> joinRequestedListener;

    /**
     * @param warnLogger sink for non-fatal warnings (never throws); may be
     *                   {@code null}
     */
    public SteamworksSteamFriendsGateway(Consumer<String> warnLogger) {
        this.warnLogger = warnLogger;
        this.steamFriends = new SteamFriends(new Callback());
        this.steamUtils = new SteamUtils(new UtilsCallback());
        this.steamUser = new SteamUser(new UserCallback());
    }

    private void warn(String message) {
        if (warnLogger != null) {
            warnLogger.accept(message);
        }
    }

    @Override
    public long localSteamId64() {
        try {
            SteamID self = steamUser.getSteamID();
            return self == null ? 0L : SteamID.getNativeHandle(self);
        } catch (RuntimeException e) {
            warn("Failed to resolve local Steam ID: " + e.getMessage());
            return 0L;
        }
    }

    @Override
    public String localPersonaName() {
        try {
            String name = steamFriends.getPersonaName();
            return name == null ? "" : name;
        } catch (RuntimeException e) {
            warn("Failed to resolve local persona name: " + e.getMessage());
            return "";
        }
    }

    @Override
    public int localPersonaStateOrdinal() {
        try {
            return steamFriends.getPersonaState().ordinal();
        } catch (RuntimeException e) {
            warn("Failed to resolve local persona state: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public int friendCount() {
        try {
            return steamFriends.getFriendCount(SteamFriends.FriendFlags.Immediate);
        } catch (RuntimeException e) {
            warn("Failed to resolve friend count: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public long friendSteamId64At(int index) {
        try {
            SteamID friend = steamFriends.getFriendByIndex(index, SteamFriends.FriendFlags.Immediate);
            return friend == null ? 0L : SteamID.getNativeHandle(friend);
        } catch (RuntimeException e) {
            warn("Failed to resolve friend at index " + index + ": " + e.getMessage());
            return 0L;
        }
    }

    @Override
    public String friendPersonaName(long steamId64) {
        try {
            String name = steamFriends.getFriendPersonaName(SteamID.createFromNativeHandle(steamId64));
            return name == null ? "" : name;
        } catch (RuntimeException e) {
            warn("Failed to resolve persona name for " + steamId64 + ": " + e.getMessage());
            return "";
        }
    }

    @Override
    public int friendPersonaStateOrdinal(long steamId64) {
        try {
            return steamFriends.getFriendPersonaState(SteamID.createFromNativeHandle(steamId64)).ordinal();
        } catch (RuntimeException e) {
            warn("Failed to resolve persona state for " + steamId64 + ": " + e.getMessage());
            return 0;
        }
    }

    @Override
    public boolean isDirectFriend(long steamId64) {
        try {
            return steamFriends.getFriendRelationship(SteamID.createFromNativeHandle(steamId64))
                    == SteamFriends.FriendRelationship.Friend;
        } catch (RuntimeException e) {
            warn("Failed to resolve friend relationship for " + steamId64 + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean friendInGame(long steamId64) {
        try {
            SteamFriends.FriendGameInfo info = new SteamFriends.FriendGameInfo();
            return steamFriends.getFriendGamePlayed(SteamID.createFromNativeHandle(steamId64), info);
        } catch (RuntimeException e) {
            warn("Failed to resolve game-played state for " + steamId64 + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<String> friendGameConnectHint(long steamId64) {
        try {
            SteamFriends.FriendGameInfo info = new SteamFriends.FriendGameInfo();
            boolean inGame = steamFriends.getFriendGamePlayed(SteamID.createFromNativeHandle(steamId64), info);
            if (inGame && info.getGamePort() != 0) {
                return Optional.of(ipToString(info.getGameIP()) + ":" + (info.getGamePort() & 0xFFFF));
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            warn("Failed to resolve game-connect hint for " + steamId64 + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void requestFriendRichPresence(long steamId64) {
        try {
            steamFriends.requestFriendRichPresence(SteamID.createFromNativeHandle(steamId64));
        } catch (RuntimeException e) {
            warn("Failed to request Rich Presence for " + steamId64 + ": " + e.getMessage());
        }
    }

    @Override
    public Optional<String> friendRichPresenceValue(long steamId64, String key) {
        try {
            String value = steamFriends.getFriendRichPresence(SteamID.createFromNativeHandle(steamId64), key);
            if (value != null && !value.isEmpty()) {
                return Optional.of(value);
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            warn("Failed to read Rich Presence key \"" + key + "\" for " + steamId64 + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean setLocalRichPresence(String key, String value) {
        try {
            return steamFriends.setRichPresence(key, value);
        } catch (RuntimeException e) {
            warn("Failed to set local Rich Presence key \"" + key + "\": " + e.getMessage());
            return false;
        }
    }

    @Override
    public void clearLocalRichPresence() {
        try {
            steamFriends.clearRichPresence();
        } catch (RuntimeException e) {
            warn("Failed to clear local Rich Presence: " + e.getMessage());
        }
    }

    @Override
    public int avatarHandle(long steamId64) {
        try {
            return steamFriends.getLargeFriendAvatar(SteamID.createFromNativeHandle(steamId64));
        } catch (RuntimeException e) {
            warn("Failed to resolve avatar handle for " + steamId64 + ": " + e.getMessage());
            return 0;
        }
    }

    @Override
    public Optional<byte[]> avatarRgba(long steamId64) {
        // Re-resolve if never cached, or if the callback flagged it dirty.
        if (dirtyAvatars.remove(steamId64) || !avatarsById.containsKey(steamId64)) {
            resolveAvatar(steamId64);
        }
        return Optional.ofNullable(avatarsById.get(steamId64));
    }

    private void resolveAvatar(long steamId64) {
        try {
            SteamID friend = SteamID.createFromNativeHandle(steamId64);
            int avatarHandle = steamFriends.getLargeFriendAvatar(friend);
            if (avatarHandle == 0) {
                return;
            }
            int[] size = new int[2];
            if (!steamUtils.getImageSize(avatarHandle, size) || size[0] <= 0 || size[1] <= 0) {
                return;
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(size[0] * size[1] * 4);
            if (steamUtils.getImageRGBA(avatarHandle, buffer)) {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                avatarsById.put(steamId64, bytes);
            }
        } catch (SteamException | RuntimeException e) {
            warn("Failed to decode avatar image for " + steamId64 + ": " + e.getMessage());
        }
    }

    @Override
    public boolean isOverlayEnabled() {
        try {
            return steamUtils.isOverlayEnabled();
        } catch (RuntimeException e) {
            warn("Failed to query overlay-enabled state: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void activateOverlayChat(long steamId64) {
        activateOverlay(steamId64, SteamFriends.OverlayToUserDialog.Chat, "chat");
    }

    @Override
    public void activateOverlayProfile(long steamId64) {
        activateOverlay(steamId64, SteamFriends.OverlayToUserDialog.SteamID, "profile");
    }

    private void activateOverlay(long steamId64, SteamFriends.OverlayToUserDialog dialog, String label) {
        try {
            if (!steamUtils.isOverlayEnabled()) {
                warn("Steam overlay is not enabled; cannot open " + label + " for " + steamId64 + ".");
                return;
            }
            steamFriends.activateGameOverlayToUser(dialog, SteamID.createFromNativeHandle(steamId64));
        } catch (RuntimeException e) {
            warn("Failed to open Steam overlay " + label + " for " + steamId64 + ": " + e.getMessage());
        }
    }

    @Override
    public boolean inviteToGame(long friendSteamId64, String connectString) {
        try {
            return steamFriends.inviteUserToGame(SteamID.createFromNativeHandle(friendSteamId64), connectString);
        } catch (RuntimeException e) {
            warn("Failed to invite " + friendSteamId64 + " to game: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void setJoinRequestedListener(BiConsumer<Long, String> listener) {
        this.joinRequestedListener = listener;
    }

    @Override
    public Set<Long> drainDirtyAvatars() {
        Set<Long> snapshot = new HashSet<>(dirtyAvatars);
        dirtyAvatars.clear();
        return snapshot;
    }

    private static String ipToString(int ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    /**
     * Receives Valve's asynchronous friend-state/avatar-delivery callbacks.
     * Never throws back into steamworks4j's own callback pump.
     */
    private final class Callback implements SteamFriendsCallback {
        @Override
        public void onPersonaStateChange(SteamID steamID, SteamFriends.PersonaChange change) {
            // No-op: the next consumer sweep re-resolves persona state regardless.
        }

        @Override
        public void onAvatarImageLoaded(SteamID steamID, int image, int width, int height) {
            dirtyAvatars.add(SteamID.getNativeHandle(steamID));
        }

        @Override
        public void onGameRichPresenceJoinRequested(SteamID steamIDFriend, String connect) {
            BiConsumer<Long, String> listener = joinRequestedListener;
            if (listener != null) {
                try {
                    listener.accept(SteamID.getNativeHandle(steamIDFriend), connect);
                } catch (RuntimeException e) {
                    warn("Join-requested listener threw for connect \"" + connect + "\": " + e.getMessage());
                }
            }
        }
    }

    /** Required by {@link SteamUtils}'s constructor; no events are consumed. */
    private static final class UtilsCallback implements SteamUtilsCallback {
    }

    /** Required by {@link SteamUser}'s constructor; no events are consumed. */
    private static final class UserCallback implements SteamUserCallback {
    }
}
