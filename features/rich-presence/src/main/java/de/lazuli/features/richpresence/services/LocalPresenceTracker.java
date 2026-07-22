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
}
