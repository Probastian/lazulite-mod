package de.lazuli.cloudsync;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure, plain-JVM-testable merge logic for the Waypoints R23 Cloud sync
 * adapter's whole-directory bundle (spec Architecture): {@code
 * config/waypoints/} is synced as one bundled envelope enumerating every
 * scope-keyed file currently present (scope file name -&gt; raw JSON text),
 * not the single fixed file {@code TweaksJsonCloudSyncAdapter} wraps.
 *
 * <p>Extracted out of {@code WaypointsJsonCloudSyncAdapter} (a private
 * nested class of {@code SteamCloudSyncClientInitializer}, awkward to
 * unit-test directly since it requires a full Minecraft/Fabric
 * composition-root context) so this one narrow, high-risk rule -- an import
 * must never delete/clobber a local-only scope's file that the imported
 * envelope simply doesn't mention -- has direct test coverage on a plain
 * JVM, mirroring {@code CrossWorldStatsOfflineBucketFilter}'s own precedent
 * for the identical reason (implementation plan Risk #3).
 */
public final class WaypointsDirectoryBundleMerger {

    private WaypointsDirectoryBundleMerger() {
    }

    /**
     * @param localFiles    the full set of scope-keyed files currently on
     *                      disk (file name -&gt; raw JSON text)
     * @param incomingFiles the just-downloaded Cloud envelope's scope-keyed
     *                      files (file name -&gt; raw JSON text)
     * @return the merged file-name-&gt;content map to write back to disk:
     *         every incoming entry overwrites/creates that file, and every
     *         local-only file not mentioned in {@code incomingFiles} is
     *         carried through byte-identical, never deleted/cleared
     */
    public static Map<String, String> mergeForImport(Map<String, String> localFiles, Map<String, String> incomingFiles) {
        Map<String, String> merged = new LinkedHashMap<>(localFiles);
        merged.putAll(incomingFiles);
        return Map.copyOf(merged);
    }
}
