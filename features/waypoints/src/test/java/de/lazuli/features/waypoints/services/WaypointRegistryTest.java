package de.lazuli.features.waypoints.services;

import de.lazuli.api.waypoints.Waypoint;
import de.lazuli.features.waypoints.config.WaypointsFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WaypointRegistryTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private WaypointRegistry newRegistry(AtomicInteger saveCount, AtomicReference<WaypointsFile> lastSaved) {
        return new WaypointRegistry(file -> {
            saveCount.incrementAndGet();
            lastSaved.set(file);
        }, () -> OVERWORLD);
    }

    @Test
    void addAssignsIdColorAndCreatedAtMillis() {
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<WaypointsFile> lastSaved = new AtomicReference<>();
        WaypointRegistry registry = newRegistry(saveCount, lastSaved);

        Waypoint waypoint = registry.add("Base", 1, 64, -2, OVERWORLD);

        assertThat(waypoint.id()).isNotBlank();
        assertThat(waypoint.color()).isEqualTo(WaypointColorAssigner.colorFor(waypoint.id()));
        assertThat(waypoint.createdAtMillis()).isGreaterThan(0L);
        assertThat(saveCount.get()).isEqualTo(1);
        assertThat(lastSaved.get().dimensions().get(OVERWORLD)).containsExactly(waypoint);
    }

    @Test
    void renameUpdatesNameOnlyAndWritesThrough() {
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<WaypointsFile> lastSaved = new AtomicReference<>();
        WaypointRegistry registry = newRegistry(saveCount, lastSaved);
        Waypoint waypoint = registry.add("Base", 1, 64, -2, OVERWORLD);

        registry.rename(waypoint.id(), "New Base");

        assertThat(saveCount.get()).isEqualTo(2);
        Waypoint renamed = registry.list(OVERWORLD).get(0);
        assertThat(renamed.name()).isEqualTo("New Base");
        assertThat(renamed.id()).isEqualTo(waypoint.id());
        assertThat(renamed.x()).isEqualTo(waypoint.x());
        assertThat(renamed.color()).isEqualTo(waypoint.color());
    }

    @Test
    void editPositionCanMoveWaypointBetweenDimensions() {
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<WaypointsFile> lastSaved = new AtomicReference<>();
        WaypointRegistry registry = newRegistry(saveCount, lastSaved);
        Waypoint waypoint = registry.add("Portal", 0, 70, 0, OVERWORLD);

        registry.editPosition(waypoint.id(), 5, 80, 5, NETHER);

        assertThat(registry.list(OVERWORLD)).isEmpty();
        List<Waypoint> netherList = registry.list(NETHER);
        assertThat(netherList).hasSize(1);
        Waypoint moved = netherList.get(0);
        assertThat(moved.id()).isEqualTo(waypoint.id());
        assertThat(moved.x()).isEqualTo(5);
        assertThat(moved.y()).isEqualTo(80);
        assertThat(moved.z()).isEqualTo(5);
        assertThat(moved.dimensionId()).isEqualTo(NETHER);
    }

    @Test
    void deleteRemovesWaypointAndWritesThrough() {
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<WaypointsFile> lastSaved = new AtomicReference<>();
        WaypointRegistry registry = newRegistry(saveCount, lastSaved);
        Waypoint waypoint = registry.add("Base", 1, 64, -2, OVERWORLD);

        registry.delete(waypoint.id());

        assertThat(registry.list(OVERWORLD)).isEmpty();
        assertThat(saveCount.get()).isEqualTo(2);
    }

    @Test
    void deleteOfUnknownIdDoesNothingAndDoesNotWrite() {
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<WaypointsFile> lastSaved = new AtomicReference<>();
        WaypointRegistry registry = newRegistry(saveCount, lastSaved);
        registry.add("Base", 1, 64, -2, OVERWORLD);
        saveCount.set(0);

        registry.delete("does-not-exist");

        assertThat(saveCount.get()).isEqualTo(0);
    }

    @Test
    void listIsIsolatedPerDimension() {
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<WaypointsFile> lastSaved = new AtomicReference<>();
        WaypointRegistry registry = newRegistry(saveCount, lastSaved);

        registry.add("Overworld Base", 0, 64, 0, OVERWORLD);
        registry.add("Nether Base", 0, 64, 0, NETHER);

        assertThat(registry.list(OVERWORLD)).hasSize(1);
        assertThat(registry.list(NETHER)).hasSize(1);
        assertThat(registry.list("minecraft:the_end")).isEmpty();
    }

    @Test
    void waypointsForCurrentDimensionDelegatesToSupplier() {
        AtomicReference<String> currentDimension = new AtomicReference<>(OVERWORLD);
        WaypointRegistry registry = new WaypointRegistry(file -> { }, currentDimension::get);
        registry.add("Overworld Base", 0, 64, 0, OVERWORLD);
        registry.add("Nether Base", 0, 64, 0, NETHER);

        assertThat(registry.waypointsForCurrentDimension()).hasSize(1);
        assertThat(registry.waypointsForCurrentDimension().get(0).name()).isEqualTo("Overworld Base");

        currentDimension.set(NETHER);

        assertThat(registry.waypointsForCurrentDimension()).hasSize(1);
        assertThat(registry.waypointsForCurrentDimension().get(0).name()).isEqualTo("Nether Base");
    }

    @Test
    void knownDimensionsReflectsDimensionsWithWaypoints() {
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<WaypointsFile> lastSaved = new AtomicReference<>();
        WaypointRegistry registry = newRegistry(saveCount, lastSaved);

        registry.add("Overworld Base", 0, 64, 0, OVERWORLD);
        registry.add("Nether Base", 0, 64, 0, NETHER);

        assertThat(registry.knownDimensions()).containsExactlyInAnyOrder(OVERWORLD, NETHER);
    }

    @Test
    void loadScopeReplacesInMemoryStateAndDoesNotLeakPreviousScope() {
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<WaypointsFile> lastSaved = new AtomicReference<>();
        WaypointRegistry registry = newRegistry(saveCount, lastSaved);
        registry.add("Scope A Base", 0, 64, 0, OVERWORLD);

        WaypointsFile scopeBFile = new WaypointsFile(1, "scope-b",
                Map.of(OVERWORLD, List.of(new Waypoint("id-b", "Scope B Base", 1, 65, 1, OVERWORLD, -1, 1L))));
        registry.loadScope("scope-b", scopeBFile);

        List<Waypoint> current = registry.list(OVERWORLD);
        assertThat(current).hasSize(1);
        assertThat(current.get(0).name()).isEqualTo("Scope B Base");
    }

    @Test
    void unloadScopeClearsInMemoryState() {
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<WaypointsFile> lastSaved = new AtomicReference<>();
        WaypointRegistry registry = newRegistry(saveCount, lastSaved);
        registry.add("Base", 0, 64, 0, OVERWORLD);

        registry.unloadScope();

        assertThat(registry.list(OVERWORLD)).isEmpty();
    }

    @Test
    void loadScopeDeepCopiesSoLaterMutationsDoNotAliasSourceFile() {
        AtomicInteger saveCount = new AtomicInteger();
        AtomicReference<WaypointsFile> lastSaved = new AtomicReference<>();
        WaypointRegistry registry = newRegistry(saveCount, lastSaved);
        Waypoint original = new Waypoint("id-1", "Base", 0, 64, 0, OVERWORLD, -1, 1L);
        WaypointsFile file = new WaypointsFile(1, "scope-a", Map.of(OVERWORLD, List.of(original)));

        registry.loadScope("scope-a", file);
        registry.add("Second", 1, 1, 1, OVERWORLD);

        assertThat(file.dimensions().get(OVERWORLD)).hasSize(1); // source unmodified
        assertThat(registry.list(OVERWORLD)).hasSize(2);
    }
}
