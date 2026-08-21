package de.lazuli.features.waypoints.services;

import de.lazuli.api.waypoints.Waypoint;
import de.lazuli.api.waypoints.WaypointCompassHook;
import de.lazuli.features.waypoints.config.WaypointsFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Runtime registry of the currently-loaded scope's waypoints (spec Public
 * API "Services surface"), mirroring {@code TweakRegistry}'s I/O-agnostic
 * shape: every mutation triggers a caller-supplied save callback
 * (constructor-injected {@code Consumer<WaypointsFile>}) rather than
 * reaching for a config path itself -- actual file write happens in the
 * platform composition root ({@code WaypointsClientInitializer}).
 *
 * <p>Holds at most one scope's dimension map resident in memory at a time
 * (spec R4/R9) -- {@link #loadScope(String, WaypointsFile)}/{@link
 * #unloadScope()} are the platform composition root's world-join/disconnect
 * lifecycle hooks.
 *
 * <p>Implements {@link WaypointCompassHook} directly: {@link
 * #waypointsForCurrentDimension()} delegates to {@link #list(String)} using
 * a constructor-injected, Minecraft-import-free {@code Supplier<String>}
 * for "what is the player's current dimension right now" (backed, on the
 * platform side, by {@code WaypointScopeResolver::currentDimensionId}) --
 * this keeps the registry itself decoupled from any specific platform's
 * connection-lifecycle machinery while still satisfying the hook's no-arg
 * shape.
 */
public final class WaypointRegistry implements WaypointCompassHook {

    /** Kept in sync with {@code WaypointsConfigIO.CURRENT_SCHEMA_VERSION}. */
    private static final int SCHEMA_VERSION = 1;

    private final Consumer<WaypointsFile> onChanged;
    private final Supplier<String> currentDimensionSupplier;

    private String scopeKey;
    private Map<String, List<Waypoint>> dimensions = new LinkedHashMap<>();

    public WaypointRegistry(Consumer<WaypointsFile> onChanged, Supplier<String> currentDimensionSupplier) {
        this.onChanged = onChanged;
        this.currentDimensionSupplier = currentDimensionSupplier;
    }

    /**
     * R9: called on world-join/server-connect. Replaces any previously
     * loaded scope's in-memory state wholesale (deep-copied, so later
     * mutations here never alias {@code file}'s own lists).
     */
    public synchronized void loadScope(String scopeKey, WaypointsFile file) {
        this.scopeKey = scopeKey;
        Map<String, List<Waypoint>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Waypoint>> entry : file.dimensions().entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        this.dimensions = copy;
    }

    /** R9: called on world-leave/disconnect. */
    public synchronized void unloadScope() {
        this.scopeKey = null;
        this.dimensions = new LinkedHashMap<>();
    }

    /** @return the current scope's waypoints in {@code dimensionId}, empty if none (R4/R17). */
    public synchronized List<Waypoint> list(String dimensionId) {
        return List.copyOf(dimensions.getOrDefault(dimensionId, List.of()));
    }

    /**
     * @return every dimension id the currently loaded scope has a (possibly
     *         empty) waypoint list for -- used by the platform's Waypoint
     *         Manager panel to populate its dimension selector (spec R20);
     *         not itself part of the spec's Public API "Services surface"
     *         list, added since that UI concern needs it and no other
     *         existing accessor exposes it
     */
    public synchronized java.util.Set<String> knownDimensions() {
        return java.util.Set.copyOf(dimensions.keySet());
    }

    @Override
    public synchronized List<Waypoint> waypointsForCurrentDimension() {
        String dimensionId = currentDimensionSupplier.get();
        if (dimensionId == null) {
            return List.of();
        }
        return list(dimensionId);
    }

    /** Auto-assigns id/color/createdAtMillis (R1/R5); write-through on every mutation (R7). */
    public synchronized Waypoint add(String name, int x, int y, int z, String dimensionId) {
        String id = UUID.randomUUID().toString();
        Waypoint waypoint = new Waypoint(id, name, x, y, z, dimensionId,
                WaypointColorAssigner.colorFor(id), System.currentTimeMillis());
        dimensions.computeIfAbsent(dimensionId, ignored -> new ArrayList<>()).add(waypoint);
        notifyChanged();
        return waypoint;
    }

    public synchronized void rename(String id, String newName) {
        mutate(id, waypoint -> new Waypoint(waypoint.id(), newName, waypoint.x(), waypoint.y(), waypoint.z(),
                waypoint.dimensionId(), waypoint.color(), waypoint.createdAtMillis()));
    }

    /** Allows moving a waypoint between dimensions (spec Public API, R21). */
    public synchronized void editPosition(String id, int x, int y, int z, String dimensionId) {
        Waypoint existing = findAndRemove(id);
        if (existing == null) {
            return;
        }
        Waypoint updated = new Waypoint(existing.id(), existing.name(), x, y, z, dimensionId,
                existing.color(), existing.createdAtMillis());
        dimensions.computeIfAbsent(dimensionId, ignored -> new ArrayList<>()).add(updated);
        notifyChanged();
    }

    public synchronized void delete(String id) {
        if (findAndRemove(id) != null) {
            notifyChanged();
        }
    }

    private void mutate(String id, UnaryOperator<Waypoint> mutator) {
        for (List<Waypoint> list : dimensions.values()) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id().equals(id)) {
                    list.set(i, mutator.apply(list.get(i)));
                    notifyChanged();
                    return;
                }
            }
        }
    }

    private Waypoint findAndRemove(String id) {
        for (List<Waypoint> list : dimensions.values()) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id().equals(id)) {
                    return list.remove(i);
                }
            }
        }
        return null;
    }

    private void notifyChanged() {
        Map<String, List<Waypoint>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<Waypoint>> entry : dimensions.entrySet()) {
            snapshot.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        onChanged.accept(new WaypointsFile(SCHEMA_VERSION, scopeKey, snapshot));
    }
}
