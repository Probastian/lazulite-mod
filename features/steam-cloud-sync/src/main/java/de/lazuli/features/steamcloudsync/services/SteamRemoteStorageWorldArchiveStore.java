package de.lazuli.features.steamcloudsync.services;

import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamRemoteStorage;
import com.codedisaster.steamworks.SteamRemoteStorageCallback;
import com.codedisaster.steamworks.SteamUGCFileWriteStreamHandle;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * The real {@link WorldArchiveCloudStore} implementation, backed directly by
 * steamworks4j's {@link SteamRemoteStorage}. This class and
 * {@link SteamRemoteStorageCloudFileStore} are the <strong>only</strong> two
 * classes in {@code features/steam-cloud-sync} that import
 * {@code com.codedisaster.steamworks.*}.
 *
 * <p>Only ever constructed when {@code SteamAvailability.isSteamAvailable()}
 * is {@code true}. Every method here must be called from the same thread
 * {@code SteamAPI.init()}/{@code pumpCallbacks()} run on (Minecraft's client
 * tick thread), per this project's single-thread Steamworks convention --
 * {@code CloudSyncWorker} is responsible for hopping back onto that thread
 * before calling into this class; this class itself performs no threading.
 *
 * <p>{@link #streamWrite(String, byte[])} chunks the write under Valve's
 * documented 100MB-per-call cap, regardless of the archive's total size
 * (FR6.3). {@link #beginAsyncRead(String, AsyncReadListener)} performs a
 * single, fully <strong>synchronous</strong> {@code ISteamRemoteStorage
 * ::FileRead} call -- <em>not</em> Valve's {@code FileReadAsync}/
 * {@code FileReadAsyncComplete} pair. This is deliberate: {@code FileReadAsync}'s
 * completion is only ever delivered from inside {@code SteamAPI.runCallbacks()}
 * (see {@code SteamworksService.pumpCallbacks()}), and this project's
 * steamworks4j fork has been observed throwing {@code SteamException}
 * ("Couldn't retrieve callback method.") from that native call specifically
 * while a chunked cloud-world-restore read was in flight -- silently
 * stranding the pending read forever (no further chunks, no failure
 * callback, the download screen's progress bar just stops). Since Steam
 * Cloud files are already fully downloaded to disk before the game launches
 * (FR6.11), a synchronous {@code FileRead} is just a fast local disk read and
 * carries no meaningful blocking cost, while completely sidestepping the
 * pump-dependent async callback path. The resulting bytes are then split
 * into {@link #READ_CHUNK_BYTES}-sized pieces purely for
 * {@link AsyncReadListener#onChunk(byte[])} progress-reporting granularity
 * (so the existing 25/50/75/100% milestone logging and progress bar in
 * {@code WorldRestoreService} keep working) -- this class no longer
 * implements any of {@link SteamRemoteStorageCallback}'s methods with
 * non-default behavior.
 *
 * <p>Every steamworks4j call site catches any unexpected
 * {@link RuntimeException}, logging via the injected {@code warningLogger}
 * and reporting failure through the normal return value/listener callback --
 * no uncaught exception ever reaches the tick/render thread (NFR2).
 *
 * <p>Usage example (constructed by {@code CloudSyncCoordinator} once Steam is
 * confirmed available):
 * <pre>{@code
 * WorldArchiveCloudStore store = new SteamRemoteStorageWorldArchiveStore(LazuliMod.LOGGER::warn);
 * store.streamWrite("lazuli-world-my_world.zip", archiveBytes);
 * }</pre>
 */
public final class SteamRemoteStorageWorldArchiveStore implements WorldArchiveCloudStore, SteamRemoteStorageCallback {

    /** Comfortably under Valve's documented 100MB-per-call write cap (FR6.3). */
    private static final int WRITE_CHUNK_BYTES = 8 * 1024 * 1024;

    /**
     * The size each {@link AsyncReadListener#onChunk(byte[])} delivery is
     * split into after the single synchronous {@code FileRead} completes --
     * purely for progress-reporting granularity (FR6.11), not a Steamworks
     * per-call limit.
     */
    private static final int READ_CHUNK_BYTES = 1 * 1024 * 1024;

    private final SteamRemoteStorage remoteStorage;
    private final Consumer<String> warningLogger;

    /**
     * @param warningLogger receives a human-readable message for every
     *                      failure mode; never invoked with a thrown
     *                      exception
     */
    public SteamRemoteStorageWorldArchiveStore(Consumer<String> warningLogger) {
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.remoteStorage = new SteamRemoteStorage(this);
        logCloudEnablementDiagnostics();
    }

    /**
     * One-time diagnostic dump of whether Steam Cloud is actually enabled for
     * this account/app, plus current quota. Steam Cloud requires quota to be
     * configured and published for the running App ID on the Steamworks
     * partner site; writes issued while it's disabled can appear to succeed
     * locally (steamworks4j returns {@code true}) without ever reaching
     * Valve's backend, so this is logged unconditionally (not only on
     * failure) to make that silent-failure mode diagnosable.
     */
    private void logCloudEnablementDiagnostics() {
        try {
            boolean accountEnabled = remoteStorage.isCloudEnabledForAccount();
            boolean appEnabled = remoteStorage.isCloudEnabledForApp();
            long[] total = new long[1];
            long[] available = new long[1];
            boolean quotaOk = remoteStorage.getQuota(total, available);
            warn("Steam Cloud diagnostics: isCloudEnabledForAccount=" + accountEnabled
                    + ", isCloudEnabledForApp=" + appEnabled
                    + ", getQuota=" + quotaOk
                    + (quotaOk ? " (total=" + total[0] + " bytes, available=" + available[0] + " bytes)" : ""));
        } catch (RuntimeException e) {
            warn("Failed to read Steam Cloud enablement diagnostics: " + e);
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean streamWrite(String fileName, byte[] data) {
        try {
            SteamUGCFileWriteStreamHandle handle = remoteStorage.fileWriteStreamOpen(fileName);
            if (handle == null) {
                warn("Steam Cloud rejected opening a write stream for \"" + fileName + "\" (" + data.length + " bytes).");
                return false;
            }

            boolean ok = true;
            int offset = 0;
            while (offset < data.length && ok) {
                int length = Math.min(WRITE_CHUNK_BYTES, data.length - offset);
                ByteBuffer chunk = ByteBuffer.allocateDirect(length);
                chunk.put(data, offset, length);
                chunk.flip();
                ok = remoteStorage.fileWriteStreamWriteChunk(handle, chunk);
                offset += length;
            }

            if (ok) {
                return remoteStorage.fileWriteStreamClose(handle);
            }
            remoteStorage.fileWriteStreamCancel(handle);
            warn("Failed to write one or more chunks of Steam Cloud world archive \"" + fileName + "\"; write cancelled "
                    + "(wrote " + offset + " of " + data.length + " bytes).");
            return false;
        } catch (RuntimeException e) {
            warn("Failed to stream-write Steam Cloud world archive \"" + fileName + "\" (" + data.length + " bytes): " + e);
            return false;
        }
    }

    @Override
    public void beginAsyncRead(String fileName, AsyncReadListener listener) {
        try {
            int size = remoteStorage.getFileSize(fileName);
            if (size <= 0) {
                listener.onFailed("World archive \"" + fileName + "\" is empty or does not exist on Steam Cloud.");
                return;
            }

            ByteBuffer buffer = ByteBuffer.allocateDirect(size);
            int read;
            try {
                read = remoteStorage.fileRead(fileName, buffer);
            } catch (SteamException e) {
                warn("Steam Cloud read failed for \"" + fileName + "\": " + e);
                listener.onFailed("Steam Cloud read failed for \"" + fileName + "\": " + e);
                return;
            }
            if (read <= 0) {
                listener.onFailed("Steam Cloud returned no data for \"" + fileName + "\".");
                return;
            }

            buffer.rewind();
            int delivered = 0;
            while (delivered < read) {
                int length = Math.min(READ_CHUNK_BYTES, read - delivered);
                byte[] chunk = new byte[length];
                buffer.get(chunk);
                listener.onChunk(chunk);
                delivered += length;
            }
            listener.onComplete();
        } catch (RuntimeException e) {
            warn("Failed to read Steam Cloud world archive \"" + fileName + "\": " + e);
            listener.onFailed("Failed to read world archive: " + e);
        }
    }

    @Override
    public int fileSize(String fileName) {
        try {
            return remoteStorage.getFileSize(fileName);
        } catch (RuntimeException e) {
            warn("Failed to read Steam Cloud world archive size for \"" + fileName + "\": " + e);
            return -1;
        }
    }

    @Override
    public OptionalLong fileTimestamp(String fileName) {
        try {
            // See SteamRemoteStorageCloudFileStore.fileTimestamp(): GetFileTimestamp()
            // returns Unix epoch *seconds*; convert to millis to match every
            // caller's epoch-millis assumption.
            long timestamp = remoteStorage.getFileTimestamp(fileName);
            return timestamp > 0 ? OptionalLong.of(SteamRemoteStorageCloudFileStore.toEpochMillis(timestamp)) : OptionalLong.empty();
        } catch (RuntimeException e) {
            warn("Failed to read Steam Cloud world archive timestamp for \"" + fileName + "\": " + e);
            return OptionalLong.empty();
        }
    }

    @Override
    public boolean getQuota(long[] totalBytes, long[] availableBytes) {
        try {
            return remoteStorage.getQuota(totalBytes, availableBytes);
        } catch (RuntimeException e) {
            warn("Failed to read Steam Cloud quota: " + e);
            return false;
        }
    }

    @Override
    public boolean forget(String fileName) {
        try {
            return remoteStorage.fileForget(fileName);
        } catch (RuntimeException e) {
            warn("Failed to forget Steam Cloud world archive \"" + fileName + "\": " + e);
            return false;
        }
    }

    @Override
    public boolean deleteWorldArchive(String fileName) {
        try {
            return remoteStorage.fileDelete(fileName);
        } catch (RuntimeException e) {
            warn("Failed to delete Steam Cloud world archive \"" + fileName + "\": " + e);
            return false;
        }
    }

    private void warn(String message) {
        warningLogger.accept(message);
    }
}
