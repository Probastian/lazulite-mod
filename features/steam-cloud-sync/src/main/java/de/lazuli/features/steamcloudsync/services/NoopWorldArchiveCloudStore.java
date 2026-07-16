package de.lazuli.features.steamcloudsync.services;

import java.util.OptionalLong;

/**
 * A {@link WorldArchiveCloudStore} that performs no Cloud I/O at all -- used
 * whenever {@code SteamAvailability.isSteamAvailable()} is {@code false} at
 * {@code CloudSyncCoordinator} construction time (FR0.1).
 *
 * <p>Usage example:
 * <pre>{@code
 * WorldArchiveCloudStore store = new NoopWorldArchiveCloudStore();
 * store.beginAsyncRead("lazuli-world-my_world.zip", listener); // immediately fails, no exception
 * }</pre>
 */
public final class NoopWorldArchiveCloudStore implements WorldArchiveCloudStore {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean streamWrite(String fileName, byte[] data) {
        return false;
    }

    @Override
    public void beginAsyncRead(String fileName, AsyncReadListener listener) {
        listener.onFailed("Steam Cloud is unavailable.");
    }

    @Override
    public int fileSize(String fileName) {
        return -1;
    }

    @Override
    public OptionalLong fileTimestamp(String fileName) {
        return OptionalLong.empty();
    }

    @Override
    public boolean getQuota(long[] totalBytes, long[] availableBytes) {
        return false;
    }

    @Override
    public boolean forget(String fileName) {
        return false;
    }
}
