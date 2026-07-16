package de.lazuli.features.steamcloudsync.services;

import de.lazuli.features.steamcloudsync.api.LastPlayedPointer;
import de.lazuli.features.steamcloudsync.config.LastPlayedPointerIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Group 4's "continue where you left off" pointer service (FR4.1-FR4.4).
 * Updated on well-defined lifecycle checkpoints -- entering/leaving a
 * singleplayer world, joining/disconnecting a multiplayer server -- each of
 * which is this group's own natural data-change checkpoint (FR0.3), so every
 * {@code recordXxx} call here persists locally and pushes to Cloud
 * immediately.
 *
 * <p>At the client-startup checkpoint, after pulling the Cloud copy, if it
 * differs from this device's own last-recorded pointer and its timestamp is
 * newer, {@code notificationListener} is invoked (FR4.3: v1 is log/toast
 * only, no dedicated screen; this service never auto-launches anything).
 * This service intentionally never overwrites this device's own
 * {@link #current()} with a newer Cloud pointer -- {@link #current()}
 * always reflects <em>this device's</em> actual last-played destination;
 * the Cloud pointer is purely a notification signal until a future
 * "Continue as..." feature (Future Extension) exists to act on it.
 *
 * <p>Usage example:
 * <pre>{@code
 * LastPlayedPointerService service = new LastPlayedPointerService(
 *         cloudFileStore, localFilePath, true, LazuliMod.LOGGER::warn,
 *         pointer -> LazuliMod.LOGGER.info("A newer session exists on {}: {}", pointer.name(), pointer));
 * service.reconcileAtStartup();
 * // ClientPlayConnectionEvents.JOIN handler (singleplayer or multiplayer):
 * service.recordWorldEntered("My World", "my_world_folder");
 * }</pre>
 */
public final class LastPlayedPointerService {

    private static final String CLOUD_FILE_NAME = "lazuli-continue-pointer.json";

    private final CloudFileStore cloudFileStore;
    private final LastPlayedPointerIO io = new LastPlayedPointerIO();
    private final Path localFilePath;
    private final boolean cloudSyncEnabled;
    private final Consumer<String> warningLogger;
    private final Consumer<LastPlayedPointer> notificationListener;
    private LastPlayedPointer current;

    /**
     * @param cloudFileStore        the Cloud seam to sync through (real or no-op)
     * @param localFilePath         this device's own local continue-pointer file
     * @param cloudSyncEnabled      whether Cloud sync is currently enabled for
     *                              this group (master switch AND'd with
     *                              {@code syncContinuePointer})
     * @param warningLogger         receives a human-readable message for any
     *                              I/O failure; never invoked with a thrown
     *                              exception
     * @param notificationListener  invoked (FR4.3) when a newer Cloud pointer
     *                              than this device's own is found at startup
     */
    public LastPlayedPointerService(
            CloudFileStore cloudFileStore,
            Path localFilePath,
            boolean cloudSyncEnabled,
            Consumer<String> warningLogger,
            Consumer<LastPlayedPointer> notificationListener) {
        this.cloudFileStore = Objects.requireNonNull(cloudFileStore, "cloudFileStore");
        this.localFilePath = Objects.requireNonNull(localFilePath, "localFilePath");
        this.cloudSyncEnabled = cloudSyncEnabled;
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
        this.notificationListener = Objects.requireNonNull(notificationListener, "notificationListener");
    }

    /** @return this device's own currently-recorded pointer, if any */
    public synchronized Optional<LastPlayedPointer> current() {
        return Optional.ofNullable(current);
    }

    /**
     * Records that this device entered a singleplayer world.
     *
     * @param worldName   the world's display name
     * @param worldFolder the world's save-folder name
     */
    public synchronized void recordWorldEntered(String worldName, String worldFolder) {
        record(new LastPlayedPointer(LastPlayedPointer.Type.WORLD, worldName, worldFolder, System.currentTimeMillis()));
    }

    /**
     * Records that this device left a singleplayer world (FR4.2's own
     * "leaving" checkpoint -- refreshes the pointer's timestamp to reflect
     * the most recent play session boundary).
     *
     * @param worldName   the world's display name
     * @param worldFolder the world's save-folder name
     */
    public synchronized void recordWorldExited(String worldName, String worldFolder) {
        record(new LastPlayedPointer(LastPlayedPointer.Type.WORLD, worldName, worldFolder, System.currentTimeMillis()));
    }

    /**
     * Records that this device joined a multiplayer server.
     *
     * @param serverName the server's configured/display name
     * @param address    the server address (host:port)
     */
    public synchronized void recordServerJoined(String serverName, String address) {
        record(new LastPlayedPointer(LastPlayedPointer.Type.SERVER, serverName, address, System.currentTimeMillis()));
    }

    /**
     * Records that this device disconnected from a multiplayer server.
     *
     * @param serverName the server's configured/display name
     * @param address    the server address (host:port)
     */
    public synchronized void recordServerDisconnected(String serverName, String address) {
        record(new LastPlayedPointer(LastPlayedPointer.Type.SERVER, serverName, address, System.currentTimeMillis()));
    }

    /**
     * Loads this device's own local pointer, then compares the Cloud copy
     * against it (FR4.3): a strictly-newer Cloud pointer triggers
     * {@code notificationListener}, never a state change to
     * {@link #current()} itself. Call once at the client-startup checkpoint
     * (FR0.3).
     */
    public synchronized void reconcileAtStartup() {
        current = readLocalFile().orElse(null);
        if (!cloudSyncEnabled) {
            return;
        }
        Optional<byte[]> cloudBytes = cloudFileStore.read(CLOUD_FILE_NAME);
        if (cloudBytes.isEmpty()) {
            return;
        }
        LastPlayedPointerIO.ParseResult result = io.parse(new String(cloudBytes.get(), StandardCharsets.UTF_8));
        if (result.warning() != null) {
            warningLogger.accept(result.warning());
        }
        result.pointer().ifPresent(cloudPointer -> {
            if (current == null || cloudPointer.timestamp() > current.timestamp()) {
                notificationListener.accept(cloudPointer);
            }
        });
    }

    /**
     * Pushes the current pointer to Cloud, if any. Call once at the
     * client-shutdown checkpoint (FR0.3) -- in practice a no-op beyond what
     * {@link #record(LastPlayedPointer)} already pushed, kept for symmetry
     * with the other groups' shutdown checkpoint.
     */
    public synchronized void syncOnShutdown() {
        if (cloudSyncEnabled && current != null) {
            cloudFileStore.write(CLOUD_FILE_NAME, serializeCurrent());
        }
    }

    private void record(LastPlayedPointer pointer) {
        current = pointer;
        try {
            Path parent = localFilePath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(localFilePath, io.serialize(pointer), StandardCharsets.UTF_8);
        } catch (IOException e) {
            warningLogger.accept("Failed to write " + localFilePath + ": " + e);
        }
        if (cloudSyncEnabled) {
            cloudFileStore.write(CLOUD_FILE_NAME, serializeCurrent());
        }
    }

    private Optional<LastPlayedPointer> readLocalFile() {
        try {
            if (Files.notExists(localFilePath)) {
                return Optional.empty();
            }
            LastPlayedPointerIO.ParseResult result = io.parse(Files.readString(localFilePath, StandardCharsets.UTF_8));
            if (result.warning() != null) {
                warningLogger.accept(result.warning());
            }
            return result.pointer();
        } catch (IOException e) {
            warningLogger.accept("Failed to read " + localFilePath + ": " + e);
            return Optional.empty();
        }
    }

    private byte[] serializeCurrent() {
        return io.serialize(current).getBytes(StandardCharsets.UTF_8);
    }
}
