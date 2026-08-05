package de.lazuli.features.steamcloudsync.services;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * A {@link CloudFileStore} that performs no Cloud I/O at all -- used whenever
 * {@code SteamAvailability.isSteamAvailable()} is {@code false} at
 * {@code CloudSyncCoordinator} construction time. Structurally satisfies
 * FR0.1 (every sync operation becomes a no-op when Steam is unavailable, with
 * all local CRUD continuing to work) without any of this feature's six
 * services needing an {@code if (steamAvailable)} branch of their own.
 *
 * <p>Usage example:
 * <pre>{@code
 * CloudFileStore store = new NoopCloudFileStore();
 * store.write("lazuli-notes.json", data); // returns false, no exception
 * }</pre>
 */
public final class NoopCloudFileStore implements CloudFileStore {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public Optional<byte[]> read(String fileName) {
        return Optional.empty();
    }

    @Override
    public boolean write(String fileName, byte[] data) {
        return false;
    }

    @Override
    public OptionalLong fileTimestamp(String fileName) {
        return OptionalLong.empty();
    }

    @Override
    public boolean delete(String fileName) {
        return false;
    }
}
