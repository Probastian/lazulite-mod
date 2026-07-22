package de.lazuli.features.richpresence.services;

import de.lazuli.api.richpresence.RichPresenceFacade;

import java.util.Optional;

/**
 * Thin, read-only pass-through implementing the {@code api}-layer
 * {@link RichPresenceFacade} (FR-RP6, plan Decision 6) -- no caching, no
 * separate poll cadence, backed directly by the same
 * {@link LocalPresenceTracker} instance {@link RichPresencePublisher} polls
 * (single source of truth).
 */
public final class RichPresenceFacadeImpl implements RichPresenceFacade {

    private final LocalPresenceTracker tracker;

    public RichPresenceFacadeImpl(LocalPresenceTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public Optional<String> localPresenceStatus() {
        return tracker.currentStatus();
    }
}
