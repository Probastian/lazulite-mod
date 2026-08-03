package de.lazuli.features.richpresence.services;

import java.util.Optional;

/**
 * Public contract (specification "Public API" item 1): recomputes and
 * returns the current tier's fully localized status string, or empty if no
 * session is active (FR-RP7).
 */
public interface LocalPresenceTracker {

    /** @return the current status string, or empty if no session is active */
    Optional<String> currentStatus();

    /**
     * Addendum FR-RPD2: exposes the currently-resolved tier's raw kind plus
     * its already-localized biome name and dimension flags, so a caller
     * (e.g. {@code RichPresencePublisher}) can select the correct
     * {@code steam_display} token and format the {@code biome}/
     * {@code dimensionSuffix} interpolation keys.
     *
     * @return the current tier snapshot, or empty if no session is active
     *         (FR-RP7, mirrors {@link #currentStatus()}'s Main Menu
     *         short-circuit)
     */
    Optional<LocalPresenceTierSnapshot> currentTier();
}
