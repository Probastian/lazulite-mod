package de.lazuli.features.waypoints.services;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WaypointColorAssignerTest {

    @Test
    void sameIdAlwaysProducesSameColor() {
        String id = UUID.randomUUID().toString();

        int first = WaypointColorAssigner.colorFor(id);
        int second = WaypointColorAssigner.colorFor(id);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void colorIsFullyOpaque() {
        int color = WaypointColorAssigner.colorFor("some-waypoint-id");

        assertThat(color & 0xFF000000).isEqualTo(0xFF000000);
    }

    @Test
    void distinctIdsSpotCheckProducesMostlyDistinctColors() {
        Set<Integer> colors = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            colors.add(WaypointColorAssigner.colorFor("waypoint-" + i));
        }

        // Not a collision-freedom proof (HSL-wheel hashing can theoretically
        // collide, per R5's own acknowledged intent) -- a basic spot-check
        // that a small, realistic waypoint count doesn't trivially collapse
        // onto one or two colors.
        assertThat(colors.size()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void nullIdDoesNotThrow() {
        assertThat(WaypointColorAssigner.colorFor(null) & 0xFF000000).isEqualTo(0xFF000000);
    }
}
