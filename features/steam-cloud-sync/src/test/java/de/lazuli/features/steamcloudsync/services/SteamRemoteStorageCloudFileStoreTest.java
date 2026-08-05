package de.lazuli.features.steamcloudsync.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the fresh-launch upload-instead-of-download bug's actual root
 * cause: {@code ISteamRemoteStorage::GetFileTimestamp()} returns Unix epoch
 * <em>seconds</em>, but {@link CloudFileStore#fileTimestamp(String)}'s
 * contract -- and every caller of it ({@link CloudSyncableReconciler},
 * {@link LocalCloudFileReconciler}) -- assumes epoch <em>milliseconds</em>,
 * the same unit {@code CloudSyncable#localLastModifiedMillis()} and
 * {@code Files.getLastModifiedTime()} use. Left unconverted, a real Cloud
 * timestamp (~1.7e9) always reads as numerically far smaller than a real
 * local timestamp (~1.7e12), so local always won the "which side is newer"
 * comparison regardless of the two files' actual relative recency -- Cloud
 * was never once downloaded, only ever overwritten.
 *
 * <p>The class under test itself constructs a real, native
 * {@code SteamRemoteStorage} in its constructor and so cannot be
 * instantiated in a plain JUnit run (NFR1's one accepted exception); this
 * test instead exercises the small, pure, package-private conversion helper
 * it delegates to.
 */
class SteamRemoteStorageCloudFileStoreTest {

    @Test
    void convertsUnixEpochSecondsToEpochMillis() {
        long steamEpochSeconds = 1_700_000_000L;

        long result = SteamRemoteStorageCloudFileStore.toEpochMillis(steamEpochSeconds);

        assertThat(result).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void aNewerCloudSecondsTimestampConvertsToANewerMillisTimestampThanAnOlderLocalOne() {
        // Regression for the exact fresh-launch scenario: Cloud was written
        // one hour after local, but before this fix the raw (unconverted)
        // Cloud seconds value would still compare as "older" than local's
        // millis value simply because it is a ~1000x smaller number.
        long localLastModifiedMillis = 1_700_000_000_000L;
        long cloudEpochSecondsOneHourLater = 1_700_003_600L;

        long cloudLastModifiedMillis = SteamRemoteStorageCloudFileStore.toEpochMillis(cloudEpochSecondsOneHourLater);

        assertThat(cloudLastModifiedMillis).isGreaterThan(localLastModifiedMillis);
    }
}
