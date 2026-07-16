package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.CloudOnlyWorldSummary;
import de.lazuli.api.cloudsync.CloudOnlyWorldsHook;
import de.lazuli.features.steamcloudsync.api.WorldFingerprint;
import de.lazuli.features.steamcloudsync.config.WorldFingerprintIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Thin {@link CloudOnlyWorldsHook} implementation composing the pure
 * {@link CloudOnlyWorldDetector} with this device's already-pulled local
 * fingerprint cache (FR6.6/FR6.8) -- the Cloud fingerprint file itself is
 * only ever pulled at the client-startup checkpoint (FR0.3), like every
 * other Cloud file; this facade just re-reads that already-local cache each
 * time it is asked, which stays cheap and synchronous on the render/client
 * thread (FR6.8).
 *
 * <p>Usage example (from a platform Version Adapter holding this facade
 * through its {@link CloudOnlyWorldsHook} contract):
 * <pre>{@code
 * CloudOnlyWorldsHook hook = new CloudOnlyWorldsFacade(fingerprintCachePath, LazuliMod.LOGGER::warn);
 * List<CloudOnlyWorldSummary> cloudOnly = hook.listCloudOnlyWorlds(localSaveFolderNames);
 * }</pre>
 */
public final class CloudOnlyWorldsFacade implements CloudOnlyWorldsHook {

    private final WorldFingerprintIO io = new WorldFingerprintIO();
    private final CloudOnlyWorldDetector detector = new CloudOnlyWorldDetector();
    private final Path fingerprintCachePath;
    private final Consumer<String> warningLogger;

    /**
     * @param fingerprintCachePath this device's local cache of the Cloud
     *                             fingerprint file (kept up to date by
     *                             {@code WorldSaveSyncService} at the
     *                             client-startup checkpoint)
     * @param warningLogger        receives a human-readable message for any
     *                             I/O failure; never invoked with a thrown
     *                             exception
     */
    public CloudOnlyWorldsFacade(Path fingerprintCachePath, Consumer<String> warningLogger) {
        this.fingerprintCachePath = Objects.requireNonNull(fingerprintCachePath, "fingerprintCachePath");
        this.warningLogger = Objects.requireNonNull(warningLogger, "warningLogger");
    }

    @Override
    public List<CloudOnlyWorldSummary> listCloudOnlyWorlds(List<String> localWorldFolderNames) {
        List<WorldFingerprint> fingerprints = readFingerprints();
        return detector.detect(localWorldFolderNames, fingerprints);
    }

    private List<WorldFingerprint> readFingerprints() {
        try {
            if (Files.notExists(fingerprintCachePath)) {
                return List.of();
            }
            String content = Files.readString(fingerprintCachePath, StandardCharsets.UTF_8);
            WorldFingerprintIO.ParseResult result = io.parse(content);
            if (result.warning() != null) {
                warningLogger.accept(result.warning());
            }
            return result.entries();
        } catch (IOException e) {
            warningLogger.accept("Failed to read " + fingerprintCachePath + ": " + e);
            return List.of();
        }
    }
}
