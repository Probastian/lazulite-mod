package de.lazuli.features.steamcloudsync.services;

import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamRemoteStorage;
import com.codedisaster.steamworks.SteamRemoteStorageCallback;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * The real {@link CloudFileStore} implementation, backed directly by
 * steamworks4j's {@link SteamRemoteStorage}. This class and
 * {@link SteamRemoteStorageWorldArchiveStore} are the <strong>only</strong>
 * two classes in {@code features/steam-cloud-sync} that import
 * {@code com.codedisaster.steamworks.*} -- every other class in this feature
 * depends only on {@link CloudFileStore}/{@link WorldArchiveCloudStore},
 * keeping NFR1's plain-JVM-testability guarantee structural.
 *
 * <p>Only ever constructed when {@code SteamAvailability.isSteamAvailable()}
 * is {@code true} (by {@code CloudSyncCoordinator}); every call here is a
 * plain, synchronous, blocking steamworks4j call (Groups 1/3/4/5 never need
 * the async read/write path Group 6 alone uses), so no
 * {@link SteamRemoteStorageCallback} dispatch is actually exercised by this
 * class -- a no-op callback is supplied purely to satisfy
 * {@link SteamRemoteStorage}'s constructor.
 *
 * <p>Every steamworks4j call site here catches {@link SteamException} and
 * any unexpected {@link RuntimeException}, logging via the injected
 * {@code warningLogger} and returning a safe empty/failure result -- no
 * uncaught exception ever reaches the tick/render thread (NFR2), the same
 * discipline {@code SteamworksService.create(...)} already established.
 *
 * <p>Usage example (constructed by {@code CloudSyncCoordinator} once Steam is
 * confirmed available):
 * <pre>{@code
 * CloudFileStore store = new SteamRemoteStorageCloudFileStore(LazuliMod.LOGGER::warn);
 * store.write("lazuli-notes.json", jsonBytes);
 * }</pre>
 */
public final class SteamRemoteStorageCloudFileStore implements CloudFileStore {

    private final SteamRemoteStorage remoteStorage;
    private final Consumer<String> warningLogger;

    /**
     * @param warningLogger receives a human-readable message for every
     *                      failure mode; never invoked with a thrown
     *                      exception
     */
    public SteamRemoteStorageCloudFileStore(Consumer<String> warningLogger) {
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.remoteStorage = new SteamRemoteStorage(new SteamRemoteStorageCallback() {
        });
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Optional<byte[]> read(String fileName) {
        try {
            int size = remoteStorage.getFileSize(fileName);
            if (size <= 0) {
                return Optional.empty();
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(size);
            int read = remoteStorage.fileRead(fileName, buffer);
            if (read <= 0) {
                return Optional.empty();
            }
            byte[] data = new byte[read];
            buffer.rewind();
            buffer.get(data);
            return Optional.of(data);
        } catch (SteamException | RuntimeException e) {
            warn("Failed to read Steam Cloud file \"" + fileName + "\": " + e);
            return Optional.empty();
        }
    }

    @Override
    public boolean write(String fileName, byte[] data) {
        try {
            ByteBuffer buffer = ByteBuffer.allocateDirect(data.length);
            buffer.put(data);
            buffer.flip();
            return remoteStorage.fileWrite(fileName, buffer);
        } catch (SteamException | RuntimeException e) {
            warn("Failed to write Steam Cloud file \"" + fileName + "\": " + e);
            return false;
        }
    }

    @Override
    public OptionalLong fileTimestamp(String fileName) {
        try {
            // ISteamRemoteStorage::GetFileTimestamp() returns Unix epoch
            // *seconds*, but every caller of this method (CloudSyncableReconciler,
            // LocalCloudFileReconciler) compares this value directly against
            // File.lastModified()-style epoch *milliseconds*. Left unconverted,
            // the Cloud side always reads as ~1000x older than local, so local
            // is always (wrongly) treated as newer -- the fresh-launch
            // upload-instead-of-download bug. Convert to millis here so the
            // contract matches every consumer's assumption.
            long timestamp = remoteStorage.getFileTimestamp(fileName);
            return timestamp > 0 ? OptionalLong.of(toEpochMillis(timestamp)) : OptionalLong.empty();
        } catch (RuntimeException e) {
            warn("Failed to read Steam Cloud file timestamp for \"" + fileName + "\": " + e);
            return OptionalLong.empty();
        }
    }

    /**
     * Converts {@code ISteamRemoteStorage::GetFileTimestamp()}'s Unix epoch
     * <em>seconds</em> return value to epoch <em>milliseconds</em>, the unit
     * every {@link CloudFileStore#fileTimestamp(String)} caller assumes.
     * Package-private and static purely so this one-line conversion --
     * otherwise buried inside a class that requires a real, initialized
     * Steam session to construct -- is directly unit-testable (NFR1).
     */
    static long toEpochMillis(long epochSeconds) {
        return epochSeconds * 1000L;
    }

    @Override
    public boolean delete(String fileName) {
        try {
            return remoteStorage.fileDelete(fileName);
        } catch (RuntimeException e) {
            warn("Failed to delete Steam Cloud file \"" + fileName + "\": " + e);
            return false;
        }
    }

    private void warn(String message) {
        warningLogger.accept(message);
    }
}
