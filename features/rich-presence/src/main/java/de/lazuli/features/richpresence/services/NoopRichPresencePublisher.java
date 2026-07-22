package de.lazuli.features.richpresence.services;

/** Disabled-state twin (Steam unavailable, or feature disabled); {@link #tick()} is a no-op. */
public final class NoopRichPresencePublisher {

    /** No-op. */
    public void tick() {
        // Intentionally empty.
    }
}
