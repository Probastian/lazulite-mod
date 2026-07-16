package de.lazuli.features.steamcloudsync.services;

import de.lazuli.api.cloudsync.CloudOnlyWorldSummary;
import de.lazuli.features.steamcloudsync.api.WorldFingerprint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, plain-JVM-testable set-difference logic for FR6.8: given the local
 * saves directory's world-folder names and the parsed Cloud fingerprint
 * list, returns every world present in the fingerprint list with no
 * matching local folder -- a "cloud-only" world.
 *
 * <p>Deliberately split out from anything Steam/Minecraft-touching so this
 * comparison is unit-testable with no native call and stays cheap and
 * synchronous when run on the render/client thread by a platform Version
 * Adapter each time the Singleplayer world-select screen is populated.
 *
 * <p>Usage example:
 * <pre>{@code
 * CloudOnlyWorldDetector detector = new CloudOnlyWorldDetector();
 * List<CloudOnlyWorldSummary> cloudOnly = detector.detect(
 *         List.of("my_world_folder"), fingerprints);
 * }</pre>
 */
public final class CloudOnlyWorldDetector {

    /**
     * @param localWorldFolderNames every world save-folder name currently
     *                              present on this device's local saves
     *                              directory
     * @param fingerprints          every world fingerprint currently known
     *                              from Steam Cloud's metadata file
     * @return every fingerprint whose {@code worldSlug} has no matching
     *         entry in {@code localWorldFolderNames}, in the same order as
     *         {@code fingerprints}; never {@code null}, empty if none
     */
    public List<CloudOnlyWorldSummary> detect(List<String> localWorldFolderNames, List<WorldFingerprint> fingerprints) {
        Set<String> localFolders = new HashSet<>(localWorldFolderNames);
        List<CloudOnlyWorldSummary> result = new ArrayList<>();
        for (WorldFingerprint fingerprint : fingerprints) {
            if (!localFolders.contains(fingerprint.worldSlug())) {
                result.add(new CloudOnlyWorldSummary(
                        fingerprint.worldSlug(),
                        fingerprint.displayName(),
                        fingerprint.deviceLabel(),
                        fingerprint.syncedAtTimestamp()));
            }
        }
        return List.copyOf(result);
    }
}
