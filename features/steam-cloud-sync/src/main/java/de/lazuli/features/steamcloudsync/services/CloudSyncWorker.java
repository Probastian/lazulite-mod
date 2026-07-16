package de.lazuli.features.steamcloudsync.services;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A single-daemon-thread background worker plus a per-tick pump queue,
 * resolving the threading split this feature's specification requires for
 * Group 6 (Architecture -- Threading): archive compression/restore
 * decompression-extraction must run off the render/client thread, but every
 * actual steamworks4j call must run on the same thread {@code SteamAPI.init()}/
 * {@code pumpCallbacks()} run on.
 *
 * <p>This is genuinely new infrastructure -- the Steamworks bootstrap itself
 * does everything on the single client tick thread, never spawning a
 * background thread. Not promoted to {@code services/}: only this one
 * feature needs background work today (graduate-on-second-use).
 *
 * <p>{@link #submitBackgroundWork(Runnable)} runs work on this worker's own
 * background thread (compression, decompression, extraction).
 * {@link #enqueueTickThreadWork(Runnable)} queues a steamworks4j call to be
 * issued later, on the client tick thread, by whichever call
 * {@link #pumpTickWork()} next makes (registered by the platform composition
 * root on {@code ClientTickEvents.END_CLIENT_TICK}, alongside the existing
 * {@code SteamworksService.pumpCallbacks()} registration).
 *
 * <p>Usage example (from the platform composition root):
 * <pre>{@code
 * CloudSyncWorker worker = new CloudSyncWorker(LazuliMod.LOGGER::warn);
 * ClientTickEvents.END_CLIENT_TICK.register(client -> worker.pumpTickWork());
 * ClientLifecycleEvents.CLIENT_STOPPING.register(client -> worker.shutdown());
 * }</pre>
 *
 * <p>Usage example (from {@code WorldSaveSyncService}, hopping back onto the
 * tick thread only for the actual Steam call):
 * <pre>{@code
 * worker.submitBackgroundWork(() -> {
 *     byte[] archive = buildZipArchive(worldFolder); // CPU/IO heavy, background thread
 *     worker.enqueueTickThreadWork(() -> archiveStore.streamWrite(fileName, archive));
 * });
 * }</pre>
 */
public final class CloudSyncWorker {

    private final ExecutorService backgroundExecutor;
    private final ConcurrentLinkedQueue<Runnable> tickThreadWork = new ConcurrentLinkedQueue<>();
    private final Consumer<String> warningLogger;
    private volatile boolean shutDown;

    /**
     * @param warningLogger receives a human-readable message for every
     *                      uncaught failure from queued work; never invoked
     *                      with a thrown exception
     */
    public CloudSyncWorker(Consumer<String> warningLogger) {
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.backgroundExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "steam-cloud-sync-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Submits work to run on this worker's background thread. Safe to call
     * from any thread. A no-op after {@link #shutdown()}.
     *
     * @param work the work to run; any {@link RuntimeException} it throws is
     *             caught and logged, never propagated
     */
    public void submitBackgroundWork(Runnable work) {
        if (shutDown) {
            return;
        }
        backgroundExecutor.execute(() -> runSafely(work, "background work"));
    }

    /**
     * Queues {@code work} to run on the client tick thread the next time
     * {@link #pumpTickWork()} is called. Safe to call from any thread
     * (typically the background thread, when it needs a steamworks4j call
     * made).
     *
     * @param work the work to run on the tick thread
     */
    public void enqueueTickThreadWork(Runnable work) {
        tickThreadWork.add(work);
    }

    /**
     * Drains and runs every currently-queued tick-thread work item. Must be
     * called from the client tick thread (registered by the platform
     * composition root on {@code ClientTickEvents.END_CLIENT_TICK}).
     */
    public void pumpTickWork() {
        Runnable work;
        while ((work = tickThreadWork.poll()) != null) {
            runSafely(work, "tick-thread work");
        }
    }

    /**
     * Shuts down the background executor, waiting briefly for in-flight work
     * to finish. Idempotent, never throws.
     */
    public void shutdown() {
        if (shutDown) {
            return;
        }
        shutDown = true;
        backgroundExecutor.shutdown();
        try {
            if (!backgroundExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                backgroundExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            backgroundExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void runSafely(Runnable work, String context) {
        try {
            work.run();
        } catch (RuntimeException e) {
            warningLogger.accept("Unhandled failure in steam-cloud-sync " + context + ": " + e);
        }
    }
}
