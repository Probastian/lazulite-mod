package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.WorldSyncStatusHook.SyncStatus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorldSyncStatusTrackerTest {

    @Test
    void defaultsToNotSyncedForAnUnseenWorld() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        assertThat(tracker.statusFor("unknown_world")).isEqualTo(SyncStatus.NOT_SYNCED);
        assertThat(tracker.lastErrorFor("unknown_world")).isNull();
    }

    @Test
    void markSyncedRecordsSyncedStatus() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markSynced("my_world");

        assertThat(tracker.statusFor("my_world")).isEqualTo(SyncStatus.SYNCED);
        assertThat(tracker.lastErrorFor("my_world")).isNull();
    }

    @Test
    void markErrorRecordsErrorStatusAndMessage() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markError("my_world", "quota exceeded");

        assertThat(tracker.statusFor("my_world")).isEqualTo(SyncStatus.SYNC_ERROR);
        assertThat(tracker.lastErrorFor("my_world")).isEqualTo("quota exceeded");
    }

    @Test
    void markSkippedTooLargeRecordsSkippedStatus() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markSkippedTooLarge("big_world");

        assertThat(tracker.statusFor("big_world")).isEqualTo(SyncStatus.SKIPPED_TOO_LARGE);
        assertThat(tracker.lastErrorFor("big_world")).isNull();
    }

    @Test
    void laterCallOverwritesEarlierStatusForTheSameSlug() {
        WorldSyncStatusTracker tracker = new WorldSyncStatusTracker();

        tracker.markError("my_world", "first failure");
        tracker.markSynced("my_world");

        assertThat(tracker.statusFor("my_world")).isEqualTo(SyncStatus.SYNCED);
        assertThat(tracker.lastErrorFor("my_world")).isNull();
    }
}
