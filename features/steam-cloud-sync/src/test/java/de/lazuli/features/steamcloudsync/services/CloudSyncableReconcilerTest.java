package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.CloudSyncable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Covers the real, timestamp-based FR0.4 reconciliation {@link CloudSyncCoordinator}
 * applies to every registered {@link CloudSyncable} (Groups 1-2) -- the path
 * verification flagged as previously exercised only by a code comment, never
 * by an automated test, since {@link CloudSyncCoordinator} always constructs
 * a real-vs-noop {@code CloudFileStore} internally from
 * {@code SteamAvailability} and offers no seam to inject a fake one for that
 * "available" scenario. This class tests the reconciliation algorithm
 * directly, against a hand-written fake {@link CloudFileStore}, with no
 * {@link CloudSyncCoordinator}/Steam involvement needed at all.
 */
class CloudSyncableReconcilerTest {

    private static final class FakeCloudFileStore implements CloudFileStore {
        final Map<String, byte[]> files = new HashMap<>();
        final Map<String, Long> timestamps = new HashMap<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Optional<byte[]> read(String fileName) {
            return Optional.ofNullable(files.get(fileName));
        }

        @Override
        public boolean write(String fileName, byte[] data) {
            files.put(fileName, data);
            return true;
        }

        @Override
        public OptionalLong fileTimestamp(String fileName) {
            Long timestamp = timestamps.get(fileName);
            return timestamp == null ? OptionalLong.empty() : OptionalLong.of(timestamp);
        }
    }

    private static final class FakeCloudSyncable implements CloudSyncable {
        byte[] exported = "local-state".getBytes();
        byte[] imported;
        long localLastModifiedMillis;

        @Override
        public String cloudSyncId() {
            return "fake-syncable";
        }

        @Override
        public byte[] exportState() {
            return exported;
        }

        @Override
        public void importState(byte[] data) {
            imported = data;
        }

        @Override
        public long localLastModifiedMillis() {
            return localLastModifiedMillis;
        }
    }

    private static final String FILE_NAME = "lazuli-cloudsync-fake-syncable.dat";

    @Test
    void cloudNewerThanLocalIsImported() {
        FakeCloudFileStore store = new FakeCloudFileStore();
        store.files.put(FILE_NAME, "cloud-state".getBytes());
        store.timestamps.put(FILE_NAME, 2_000L);

        FakeCloudSyncable syncable = new FakeCloudSyncable();
        syncable.localLastModifiedMillis = 1_000L;

        CloudSyncableReconciler.reconcileAtStartup(store, FILE_NAME, syncable, true, w -> { });

        assertThat(syncable.imported).isEqualTo("cloud-state".getBytes());
    }

    @Test
    void localNewerThanCloudIsPushed() {
        FakeCloudFileStore store = new FakeCloudFileStore();
        store.files.put(FILE_NAME, "stale-cloud-state".getBytes());
        store.timestamps.put(FILE_NAME, 1_000L);

        FakeCloudSyncable syncable = new FakeCloudSyncable();
        syncable.localLastModifiedMillis = 2_000L;

        CloudSyncableReconciler.reconcileAtStartup(store, FILE_NAME, syncable, true, w -> { });

        assertThat(syncable.imported).isNull();
        assertThat(store.files.get(FILE_NAME)).isEqualTo("local-state".getBytes());
    }

    @Test
    void noCloudCopyYetPushesLocalStateIfAnyExists() {
        FakeCloudFileStore store = new FakeCloudFileStore();
        FakeCloudSyncable syncable = new FakeCloudSyncable();
        syncable.localLastModifiedMillis = 500L;

        CloudSyncableReconciler.reconcileAtStartup(store, FILE_NAME, syncable, true, w -> { });

        assertThat(store.files.get(FILE_NAME)).isEqualTo("local-state".getBytes());
    }

    @Test
    void noLocalStateAndNoCloudCopyIsANoOp() {
        FakeCloudFileStore store = new FakeCloudFileStore();
        FakeCloudSyncable syncable = new FakeCloudSyncable();
        syncable.localLastModifiedMillis = -1L;

        CloudSyncableReconciler.reconcileAtStartup(store, FILE_NAME, syncable, true, w -> { });

        assertThat(store.files).isEmpty();
        assertThat(syncable.imported).isNull();
    }

    @Test
    void disabledSyncIsANoOpRegardlessOfTimestamps() {
        FakeCloudFileStore store = new FakeCloudFileStore();
        store.files.put(FILE_NAME, "cloud-state".getBytes());
        store.timestamps.put(FILE_NAME, 9_999L);

        FakeCloudSyncable syncable = new FakeCloudSyncable();
        syncable.localLastModifiedMillis = 1L;

        CloudSyncableReconciler.reconcileAtStartup(store, FILE_NAME, syncable, false, w -> { });

        assertThat(syncable.imported).isNull();
    }

    @Test
    void pushOnShutdownPushesUnconditionallyWhenEnabled() {
        FakeCloudFileStore store = new FakeCloudFileStore();
        store.files.put(FILE_NAME, "old-cloud-state".getBytes());
        store.timestamps.put(FILE_NAME, 9_999L);

        FakeCloudSyncable syncable = new FakeCloudSyncable();
        syncable.localLastModifiedMillis = 1L;

        CloudSyncableReconciler.pushOnShutdown(store, FILE_NAME, syncable, true, w -> { });

        assertThat(store.files.get(FILE_NAME)).isEqualTo("local-state".getBytes());
    }

    @Test
    void pushOnShutdownIsANoOpWhenDisabled() {
        FakeCloudFileStore store = new FakeCloudFileStore();
        FakeCloudSyncable syncable = new FakeCloudSyncable();

        CloudSyncableReconciler.pushOnShutdown(store, FILE_NAME, syncable, false, w -> { });

        assertThat(store.files).isEmpty();
    }

    @Test
    void neverThrowsWhenImportStateThrows() {
        FakeCloudFileStore store = new FakeCloudFileStore();
        store.files.put(FILE_NAME, "cloud-state".getBytes());
        store.timestamps.put(FILE_NAME, 2_000L);

        CloudSyncable throwing = new CloudSyncable() {
            @Override
            public String cloudSyncId() {
                return "throwing-syncable";
            }

            @Override
            public byte[] exportState() {
                return "local-state".getBytes();
            }

            @Override
            public void importState(byte[] data) {
                throw new RuntimeException("boom");
            }

            @Override
            public long localLastModifiedMillis() {
                return 1_000L;
            }
        };

        assertThatCode(() -> CloudSyncableReconciler.reconcileAtStartup(store, FILE_NAME, throwing, true, w -> { }))
                .doesNotThrowAnyException();
    }
}
