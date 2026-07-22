package de.lazuli.features.richpresence.services;

/**
 * Every resolvable status tier (specification "Status tiers" / "Tier
 * Priority / Precedence"). {@link #MAIN_MENU} is a sentinel meaning "no
 * session active" -- {@code LocalPresenceTrackerImpl} maps it to
 * {@code Optional.empty()} rather than ever handing it to a
 * {@code TierTextFormatter} (FR-RP7).
 */
public enum TierKind {
    MAIN_MENU,
    PAUSED,
    SPECTATING,
    RIDING_MINECART,
    RIDING_BOAT,
    NEAR_VILLAGE,
    EXPLORING,
    STAYING,
    BUILDING,
    DIGGING_AROUND
}
