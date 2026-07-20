package de.lazuli.api.worldhosting;

/**
 * A plain, immutable snapshot of the local player's Steam World Hosting state,
 * queryable by a platform composition root to decide whether to advertise Rich
 * Presence or enable UI affordances (spec Public API item 1). Carries no
 * {@code net.minecraft.*}/steamworks4j type, mirroring {@code SteamAvailability}'s
 * own "stable, dependency-free contract" rationale.
 *
 * @param hosting        {@code true} while a singleplayer world is loaded and
 *                       the Steam P2P listener is standing (FR1.2)
 * @param localSteamId64 the local player's own {@code SteamID64}, or {@code 0}
 *                       when not hosting / Steam unavailable
 */
public record HostedWorldStatus(boolean hosting, long localSteamId64) {

    /** The not-hosting / Steam-unavailable snapshot. */
    public static final HostedWorldStatus NOT_HOSTING = new HostedWorldStatus(false, 0L);
}
