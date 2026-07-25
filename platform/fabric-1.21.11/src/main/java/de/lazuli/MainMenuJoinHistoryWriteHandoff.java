package de.lazuli;

import de.lazuli.features.mainmenu.config.MainMenuJoinHistoryConfig;

import java.util.function.Consumer;

/**
 * Narrow, composition-root-scoped hand-off publishing two write-side
 * callbacks -- one for real server joins, one for friend-initiated joins --
 * so {@code ServerJoinPresenceClientInitializer} (a different feature's
 * composition-root file) can record join-history entries without either
 * module importing the other (batch-3-fixes Item BF4, Decision 6).
 *
 * <p>Unlike every other {@code *Handoff} in this repo, {@link #ifPublished}
 * is deliberately null-tolerant, not throwing: this handoff is published by
 * {@code MainMenuClientInitializer}, which itself depends on
 * {@code ServerJoinPresenceClientInitializer} having already run
 * (see that class's own composition-root ordering) -- by the time any real
 * {@code JOIN}/friend-route event fires, the game has already reached the
 * main menu at least once, so both entrypoints have already resolved
 * regardless of raw entrypoint-array order; this null-tolerant accessor is
 * still the safer default in case that assumption is ever violated.
 */
public final class MainMenuJoinHistoryWriteHandoff {

    private static volatile Consumer<MainMenuJoinHistoryConfig.ServerJoinEntry> serverJoinWriter;
    private static volatile Consumer<MainMenuJoinHistoryConfig.FriendJoinEntry> friendJoinWriter;

    private MainMenuJoinHistoryWriteHandoff() {
    }

    /** Published once by {@code MainMenuClientInitializer}. */
    public static void publish(Consumer<MainMenuJoinHistoryConfig.ServerJoinEntry> serverJoinWriter,
            Consumer<MainMenuJoinHistoryConfig.FriendJoinEntry> friendJoinWriter) {
        MainMenuJoinHistoryWriteHandoff.serverJoinWriter = serverJoinWriter;
        MainMenuJoinHistoryWriteHandoff.friendJoinWriter = friendJoinWriter;
    }

    /** No-op if not yet published. */
    public static void ifPublishedServerJoin(MainMenuJoinHistoryConfig.ServerJoinEntry entry) {
        Consumer<MainMenuJoinHistoryConfig.ServerJoinEntry> writer = serverJoinWriter;
        if (writer != null) {
            writer.accept(entry);
        }
    }

    /** No-op if not yet published. */
    public static void ifPublishedFriendJoin(MainMenuJoinHistoryConfig.FriendJoinEntry entry) {
        Consumer<MainMenuJoinHistoryConfig.FriendJoinEntry> writer = friendJoinWriter;
        if (writer != null) {
            writer.accept(entry);
        }
    }
}
