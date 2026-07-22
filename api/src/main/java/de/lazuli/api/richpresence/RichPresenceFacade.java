package de.lazuli.api.richpresence;

import java.util.Optional;

/**
 * The sole cross-feature-visible surface of {@code features/rich-presence}
 * (specification "Public API" item 2, FR-RP6): a thin, read-only accessor for
 * the same computed local-presence status string this feature publishes to
 * Steam's Rich Presence {@code "status"} key, exposed so
 * {@code features/friends-sidebar}'s own-profile row may optionally consume
 * it in place of a generic fallback label. No other feature depends on this
 * one; this is the only surface that crosses feature boundaries.
 *
 * <p>Published via a per-platform-module hand-off (mirroring every other
 * cross-feature bridge in this codebase), backed by whichever
 * {@code LocalPresenceTracker} instance the rich-presence feature's own
 * per-tick sweep already maintains -- no separate computation, no separate
 * poll cadence (single source of truth, per the specification's Goals).
 */
public interface RichPresenceFacade {

    /**
     * @return the most recently computed, fully localized status string (the
     *         same value written to Steam's {@code "status"} Rich Presence
     *         key), or empty if no session is currently active (main menu) or
     *         Steam is unavailable
     */
    Optional<String> localPresenceStatus();
}
