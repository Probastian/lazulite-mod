package de.lazuli.features.richpresence.services;

import java.util.Optional;

/** Disabled-state twin (Steam unavailable, or feature disabled), per repo convention. */
public final class NoopLocalPresenceTracker implements LocalPresenceTracker {

    @Override
    public Optional<String> currentStatus() {
        return Optional.empty();
    }

    @Override
    public Optional<LocalPresenceTierSnapshot> currentTier() {
        return Optional.empty();
    }
}
