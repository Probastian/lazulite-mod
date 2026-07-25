package de.lazuli.features.mainmenu.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted join-history record (batch-3-fixes Item BF4, FR-BF4.1): a
 * bounded, most-recent-first list of real saved-server joins and
 * friend-initiated joins, keyed by server IP / friend Steam ID
 * respectively. Immutable; {@link #upsertServer(ServerJoinEntry)}/
 * {@link #upsertFriend(FriendJoinEntry)} return a new instance.
 *
 * @param servers upsert-by-ip, most-recent-first, capped at {@link #MAX_ENTRIES}
 * @param friends upsert-by-steamId64, most-recent-first, capped at {@link #MAX_ENTRIES}
 */
public record MainMenuJoinHistoryConfig(List<ServerJoinEntry> servers, List<FriendJoinEntry> friends) {

    public static final int MAX_ENTRIES = 50;

    public static final MainMenuJoinHistoryConfig EMPTY = new MainMenuJoinHistoryConfig(List.of(), List.of());

    public MainMenuJoinHistoryConfig {
        servers = servers == null ? List.of() : List.copyOf(servers);
        friends = friends == null ? List.of() : List.copyOf(friends);
    }

    /** One real join of a saved server, keyed by {@code ip}. */
    public record ServerJoinEntry(String ip, String name, long lastJoinedEpochMillis) { }

    /** One real friend-initiated join, keyed by {@code steamId64}. */
    public record FriendJoinEntry(long steamId64, long lastPlayedTogetherEpochMillis) { }

    /**
     * @return a new config with {@code entry} upserted by {@code ip}
     * (replacing any existing entry for that ip), re-sorted most-recent-first,
     * capped at {@link #MAX_ENTRIES} (oldest evicted first)
     */
    public MainMenuJoinHistoryConfig upsertServer(ServerJoinEntry entry) {
        List<ServerJoinEntry> updated = new ArrayList<>();
        for (ServerJoinEntry existing : servers) {
            if (!existing.ip().equals(entry.ip())) {
                updated.add(existing);
            }
        }
        updated.add(entry);
        updated.sort((a, b) -> Long.compare(b.lastJoinedEpochMillis(), a.lastJoinedEpochMillis()));
        if (updated.size() > MAX_ENTRIES) {
            updated = updated.subList(0, MAX_ENTRIES);
        }
        return new MainMenuJoinHistoryConfig(updated, friends);
    }

    /**
     * @return a new config with {@code entry} upserted by {@code steamId64}
     * (replacing any existing entry for that friend), re-sorted
     * most-recent-first, capped at {@link #MAX_ENTRIES} (oldest evicted first)
     */
    public MainMenuJoinHistoryConfig upsertFriend(FriendJoinEntry entry) {
        List<FriendJoinEntry> updated = new ArrayList<>();
        for (FriendJoinEntry existing : friends) {
            if (existing.steamId64() != entry.steamId64()) {
                updated.add(existing);
            }
        }
        updated.add(entry);
        updated.sort((a, b) -> Long.compare(b.lastPlayedTogetherEpochMillis(), a.lastPlayedTogetherEpochMillis()));
        if (updated.size() > MAX_ENTRIES) {
            updated = updated.subList(0, MAX_ENTRIES);
        }
        return new MainMenuJoinHistoryConfig(servers, updated);
    }
}
