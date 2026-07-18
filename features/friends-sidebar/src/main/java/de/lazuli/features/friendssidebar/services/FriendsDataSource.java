package de.lazuli.features.friendssidebar.services;

import de.lazuli.api.friends.FriendActionListener;
import de.lazuli.api.friends.FriendSummary;

import java.util.List;
import java.util.Optional;

/**
 * Shared shape between {@link FriendsService} (the real, steamworks4j-backed
 * implementation) and {@link NoopFriendsService} (used whenever Steam is
 * unavailable or this feature's config disables it, FR0.2/FR0.3) -- lets
 * {@code FriendsSidebarFacade} and every platform composition root depend on
 * one type regardless of which was constructed, mirroring
 * {@code features/steam-cloud-sync}'s own {@code CloudFileStore}/
 * {@code NoopCloudFileStore} pairing shape.
 *
 * <p>Usage example:
 * <pre>{@code
 * FriendsDataSource dataSource = steamAvailable && config.enabled()
 *         ? new FriendsService(config, LazuliMod.LOGGER::warn)
 *         : new NoopFriendsService();
 * }</pre>
 */
public interface FriendsDataSource extends FriendActionListener {

    /**
     * Called once per client tick (Decision 7) -- internally rate-limits its
     * own refresh sweep to {@code refreshIntervalSeconds}; always a fast,
     * non-blocking call.
     */
    void tick();

    /**
     * @return the most recently resolved friend-list snapshot (FR1.1/FR1.2),
     *         never {@code null} (empty if unavailable/not yet refreshed)
     */
    List<FriendSummary> currentFriends();

    /**
     * @param steamId64 the friend to look up
     * @return the most recently delivered raw RGBA avatar bytes for this
     *         friend (FR1.3), or empty if not yet available
     */
    Optional<byte[]> avatarRgba(long steamId64);
}
