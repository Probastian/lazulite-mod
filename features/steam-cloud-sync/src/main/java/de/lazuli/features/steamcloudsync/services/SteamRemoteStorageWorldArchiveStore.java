package de.lazuli.features.steamcloudsync.services;

import com.codedisaster.steamworks.SteamAPICall;
import com.codedisaster.steamworks.SteamRemoteStorage;
import com.codedisaster.steamworks.SteamRemoteStorageCallback;
import com.codedisaster.steamworks.SteamResult;
import com.codedisaster.steamworks.SteamUGCFileWriteStreamHandle;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
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
 * (FR6.3). {@link #beginAsyncRead(String, AsyncReadListener)} drives a
 * repeated {@code fileReadAsync}/{@code fileReadAsyncComplete} chunked loop,
 * dispatched via this class's own {@link SteamRemoteStorageCallback}
 * implementation -- delivered only while the shared
 * {@code SteamworksService.pumpCallbacks()} keeps running on the client tick
 * thread.
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

    /** A conservative per-chunk size for the async read loop (FR6.11). */
    private static final int READ_CHUNK_BYTES = 1 * 1024 * 1024;

    private final SteamRemoteStorage remoteStorage;
    private final Consumer<String> warningLogger;
    private final ConcurrentHashMap<SteamAPICall, PendingRead> pendingReads = new ConcurrentHashMap<>();

    /**
     * @param warningLogger receives a human-readable message for every
     *                      failure mode; never invoked with a thrown
     *                      exception
     */
    public SteamRemoteStorageWorldArchiveStore(Consumer<String> warningLogger) {
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.remoteStorage = new SteamRemoteStorage(this);
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
                warn("Steam Cloud rejected opening a write stream for \"" + fileName + "\".");
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
            warn("Failed to write one or more chunks of Steam Cloud world archive \"" + fileName + "\"; write cancelled.");
            return false;
        } catch (RuntimeException e) {
            warn("Failed to stream-write Steam Cloud world archive \"" + fileName + "\": " + e);
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
            requestNextChunk(fileName, 0, size, listener);
        } catch (RuntimeException e) {
            warn("Failed to begin reading Steam Cloud world archive \"" + fileName + "\": " + e);
            listener.onFailed("Failed to begin reading world archive: " + e);
        }
    }

    private void requestNextChunk(String fileName, int offset, int totalSize, AsyncReadListener listener) {
        int toRead = Math.min(READ_CHUNK_BYTES, totalSize - offset);
        SteamAPICall call = remoteStorage.fileReadAsync(fileName, offset, toRead);
        if (call == null || !call.isValid()) {
            listener.onFailed("Steam Cloud rejected the read request for \"" + fileName + "\".");
            return;
        }
        pendingReads.put(call, new PendingRead(fileName, offset, totalSize, listener));
    }

    @Override
    public void onFileReadAsyncComplete(SteamAPICall call, SteamResult result, int offset, int read) {
        PendingRead pending = pendingReads.remove(call);
        if (pending == null) {
            // Not a call this instance issued (or already handled); ignore.
            return;
        }
        if (result != SteamResult.OK || read <= 0) {
            pending.listener.onFailed("Steam Cloud read failed (" + result + ") for \"" + pending.fileName + "\".");
            return;
        }
        try {
            ByteBuffer buffer = ByteBuffer.allocateDirect(read);
            boolean completed = remoteStorage.fileReadAsyncComplete(call, buffer, read);
            if (!completed) {
                pending.listener.onFailed("Failed to complete Steam Cloud read for \"" + pending.fileName + "\".");
                return;
            }
            byte[] chunk = new byte[read];
            buffer.rewind();
            buffer.get(chunk);
            pending.listener.onChunk(chunk);

            int nextOffset = pending.offset + read;
            if (nextOffset >= pending.totalSize) {
                pending.listener.onComplete();
            } else {
                requestNextChunk(pending.fileName, nextOffset, pending.totalSize, pending.listener);
            }
        } catch (RuntimeException e) {
            warn("Unexpected failure completing Steam Cloud read for \"" + pending.fileName + "\": " + e);
            pending.listener.onFailed("Unexpected failure completing Steam Cloud read: " + e);
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
            long timestamp = remoteStorage.getFileTimestamp(fileName);
            return timestamp > 0 ? OptionalLong.of(timestamp) : OptionalLong.empty();
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

    private void warn(String message) {
        warningLogger.accept(message);
    }

    private record PendingRead(String fileName, int offset, int totalSize, AsyncReadListener listener) {
    }
}
