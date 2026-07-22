package de.lazuli.services.steamworks;

import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The shared, {@code services}-layer seam owning this process's single
 * {@code SteamFriends}/{@code SteamUtils}/{@code SteamUser} instances and their
 * callback registrations -- the first {@code services}-layer extraction since
 * {@link SteamworksService} itself, triggered by this repo's
 * "graduate-on-second-use" rule now that both {@code features/friends-sidebar}
 * and {@code features/steam-world-hosting} need the same capability (Steam
 * World Hosting plan Decision 1).
 *
 * <p>Its public surface is <strong>entirely plain-Java-typed</strong>
 * ({@code long}/{@code int}/{@code String}/{@code Optional}/{@code Set}, never
 * {@code SteamID}/{@code PersonaState}/{@code FriendRelationship}) so every
 * consuming feature stays fully steamworks4j-import-free: the sole class in the
 * codebase importing {@code com.codedisaster.steamworks.*} for friends/identity
 * is {@link SteamworksSteamFriendsGateway}, alongside {@link SteamworksService}
 * itself for the bootstrap.
 *
 * <p>Constructed once by a platform composition root
 * ({@code SteamworksClientInitializer}) -- the real
 * {@link SteamworksSteamFriendsGateway} when
 * {@code SteamAvailability.isSteamAvailable()}, otherwise
 * {@link NoopSteamFriendsGateway} -- and handed to consumers via a per-module
 * hand-off. Every method is non-throwing.
 */
public interface SteamFriendsGateway {

    /** @return the local player's own {@code SteamID64}, or {@code 0} if unresolved. */
    long localSteamId64();

    /** @return the local player's own persona (display) name, or {@code ""} if unresolved. */
    String localPersonaName();

    /** @return the local player's own {@code SteamFriends.PersonaState} ordinal (0-7). */
    int localPersonaStateOrdinal();

    /** @return the number of immediate friends, or {@code 0} if unavailable. */
    int friendCount();

    /**
     * @param index a friend index in {@code [0, friendCount())}
     * @return that friend's {@code SteamID64}, or {@code 0} if out of range/unavailable
     */
    long friendSteamId64At(int index);

    /**
     * @param steamId64 the friend to look up
     * @return that friend's persona (display) name, or {@code ""} if unresolved
     */
    String friendPersonaName(long steamId64);

    /**
     * @param steamId64 the friend to look up
     * @return that friend's {@code SteamFriends.PersonaState} ordinal (0-7)
     */
    int friendPersonaStateOrdinal(long steamId64);

    /**
     * @param steamId64 the friend to test
     * @return {@code true} if that {@code SteamID64} is a direct Steam friend
     *         of the local player ({@code FriendRelationship.Friend}) -- FR1.3
     */
    boolean isDirectFriend(long steamId64);

    /**
     * @param steamId64 the friend to test
     * @return {@code true} if that friend is currently playing a game
     */
    boolean friendInGame(long steamId64);

    /**
     * @param steamId64 the friend to look up
     * @return the friend's {@code ip:port} game-connect hint (from
     *         {@code getFriendGamePlayed}), or empty if not in a joinable game
     */
    Optional<String> friendGameConnectHint(long steamId64);

    /**
     * @param steamId64 the friend to look up
     * @return the friend's current {@code FriendGameInfo.getGameID()} value
     *         if {@code getFriendGamePlayed} reports them as in a game, else
     *         {@code 0L}; never throws
     */
    long friendGameAppId(long steamId64);

    /**
     * Asks Steam to refresh its locally-cached Rich Presence for the given
     * friend (Rich Presence only refreshes on explicit request).
     *
     * @param steamId64 the friend to refresh
     */
    void requestFriendRichPresence(long steamId64);

    /**
     * @param steamId64 the friend to look up
     * @param key       the Rich Presence key (e.g. {@code "status"}, {@code "connect"})
     * @return that key's current value, or empty if unset/blank/unavailable
     */
    Optional<String> friendRichPresenceValue(long steamId64, String key);

    /**
     * Sets one of the local player's own Rich Presence keys (FR2.1).
     *
     * @param key   the Rich Presence key
     * @param value the value
     * @return {@code true} on success
     */
    boolean setLocalRichPresence(String key, String value);

    /** Clears all of the local player's own Rich Presence keys (FR2.2). */
    void clearLocalRichPresence();

    /**
     * @param steamId64 the user to look up
     * @return that user's large-avatar Steam image handle, or {@code 0} if not
     *         yet loaded
     */
    int avatarHandle(long steamId64);

    /**
     * @param steamId64 the user to look up
     * @return the raw RGBA bytes of that user's large avatar, or empty if not
     *         yet available (resolved on demand and cached internally)
     */
    Optional<byte[]> avatarRgba(long steamId64);

    /** @return {@code true} if the Steam overlay is currently enabled. */
    boolean isOverlayEnabled();

    /**
     * Opens Steam's overlay chat dialog for the given user.
     *
     * @param steamId64 the user to chat with
     */
    void activateOverlayChat(long steamId64);

    /**
     * Opens Steam's overlay profile dialog for the given user.
     *
     * @param steamId64 the user whose profile to open
     */
    void activateOverlayProfile(long steamId64);

    /**
     * Sends a real Steam invite for the given connect string to the given
     * friend ({@code SteamFriends.inviteUserToGame(SteamID, String)}).
     * Never throws; fails closed to {@code false}.
     *
     * @param friendSteamId64 the friend to invite
     * @param connectString   the Rich-Presence-style connect string to embed
     *                        in the invite (see
     *                        {@code ConnectStringCodec.encode(long)})
     * @return {@code true} on success
     */
    boolean inviteToGame(long friendSteamId64, String connectString);

    /**
     * Registers a listener for Steam's own "Join Game" overlay callback
     * ({@code onGameRichPresenceJoinRequested}, FR3.1 path 1). The listener
     * receives the inviting friend's {@code SteamID64} and the raw Rich
     * Presence {@code "connect"} string; the caller (which owns this feature's
     * connect-string format) decodes it. At most one listener; a later call
     * replaces the earlier one. No-op on the {@code Noop} gateway.
     *
     * @param listener {@code (friendSteamId64, connectString)} sink, or
     *                 {@code null} to clear
     */
    void setJoinRequestedListener(BiConsumer<Long, String> listener);

    /**
     * Returns and clears the set of user {@code SteamID64}s whose avatar Steam
     * has (re)loaded since the last call, so callers can invalidate any cached
     * texture. Replaces {@code FriendsService}'s own former {@code dirtyAvatars}
     * field.
     *
     * @return a snapshot of the newly-loaded avatar owners (possibly empty)
     */
    Set<Long> drainDirtyAvatars();
}
