package de.lazuli.features.steamcloudsync.services;

import de.lazuli.features.steamcloudsync.config.CloudSyncableUploadStateIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The FR-T.5 upload-gating state for {@code options.txt}/{@code servers.dat}/
 * cross-world-stats: a small, persisted, per-{@code cloudSyncId()} record of
 * "what local mtime did this device last successfully upload," used to skip
 * a would-be-unconditional upload at startup reconcile or shutdown when the
 * local file hasn't actually changed since then (F16's confirmed bug fix).
 *
 * <p>Mtime-based (not content-hash), consistent with FR-P3's own mtime-based
 * approach for world saves -- simpler, and this feature's own steer per the
 * spec's mechanism decision. Unlike {@link WorldSyncStatusTracker}'s
 * session-only in-progress flags, this state is persisted across a client
 * restart: an in-memory-only flag would make every startup look "changed
 * since last upload" the first time it's checked in a new process.
 *
 * <p>Package-private: used internally by {@link CloudSyncableReconciler}/
 * {@link CloudSyncCoordinator}.
 *
 * <p>Usage example:
 * <pre>{@code
 * CloudSyncableUploadGate gate = new CloudSyncableUploadGate(
 *         featureConfigDir.resolve("cloudsyncable-upload-state.json"), LazuliMod.LOGGER::warn);
 * if (gate.hasChangedSinceLastUpload("options", localTimestamp)) {
 *     // upload, then:
 *     gate.recordUploadedState("options", localTimestamp);
 * }
 * }</pre>
 */
final class CloudSyncableUploadGate {

    private final CloudSyncableUploadStateIO io = new CloudSyncableUploadStateIO();
    private final Path statePath;
    private final Consumer<String> warningLogger;

    /**
     * @param statePath     this feature's persisted per-adapter last-uploaded
     *                      state file (e.g.
     *                      {@code featureConfigDir.resolve("cloudsyncable-upload-state.json")})
     * @param warningLogger receives a human-readable message for any I/O
     *                      failure; never invoked with a thrown exception
     */
    CloudSyncableUploadGate(Path statePath, Consumer<String> warningLogger) {
        this.statePath = Objects.requireNonNull(statePath, "statePath");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
    }

    /**
     * @param cloudSyncId          the stable per-adapter identifier (e.g.
     *                             {@code "options"})
     * @param currentLocalTimestamp the local file's current last-modified
     *                             time, epoch millis
     * @return {@code true} if this device has never recorded a successful
     *         upload for {@code cloudSyncId} (first-run fallback -- treated
     *         as changed, matching today's existing unconditional-upload
     *         behavior for that one first pass), or if
     *         {@code currentLocalTimestamp} is newer than the last recorded
     *         upload; {@code false} if unchanged
     */
    boolean hasChangedSinceLastUpload(String cloudSyncId, long currentLocalTimestamp) {
        Long lastUploaded = readState().get(cloudSyncId);
        return lastUploaded == null || currentLocalTimestamp > lastUploaded;
    }

    /**
     * Records {@code uploadedLocalTimestamp} as the local mtime this device
     * just successfully uploaded for {@code cloudSyncId}, persisted
     * immediately.
     *
     * @param cloudSyncId            the stable per-adapter identifier
     * @param uploadedLocalTimestamp the local file's last-modified time at
     *                               upload time, epoch millis
     */
    void recordUploadedState(String cloudSyncId, long uploadedLocalTimestamp) {
        Map<String, Long> state = new LinkedHashMap<>(readState());
        state.put(cloudSyncId, uploadedLocalTimestamp);
        writeState(state);
    }

    private Map<String, Long> readState() {
        try {
            if (Files.notExists(statePath)) {
                return Map.of();
            }
            CloudSyncableUploadStateIO.ParseResult result = io.parse(Files.readString(statePath, StandardCharsets.UTF_8));
            if (result.warning() != null) {
                warningLogger.accept(result.warning());
            }
            return result.entries();
        } catch (IOException e) {
            warningLogger.accept("Failed to read " + statePath + ": " + e);
            return Map.of();
        }
    }

    private void writeState(Map<String, Long> state) {
        try {
            Path parent = statePath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(statePath, io.serialize(state), StandardCharsets.UTF_8);
        } catch (IOException e) {
            warningLogger.accept("Failed to write " + statePath + ": " + e);
        }
    }
}
