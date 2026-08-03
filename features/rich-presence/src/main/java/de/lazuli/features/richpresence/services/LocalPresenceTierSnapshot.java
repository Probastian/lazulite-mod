package de.lazuli.features.richpresence.services;

/**
 * The currently-resolved tier's raw kind plus its already-localized biome
 * name and dimension flags (Addendum FR-RPD2), exposed alongside the
 * pre-formatted {@link LocalPresenceTracker#currentStatus()} string so
 * {@code RichPresencePublisher} can select the correct {@code steam_display}
 * token and format the {@code biome}/{@code dimensionSuffix} interpolation
 * keys without importing any {@code net.minecraft.*} class itself.
 *
 * @param kind           the resolved tier
 * @param localizedBiome the tier's localized biome display name (e.g.
 *                       {@code "Plains"}), or {@code ""} if this tier
 *                       carries no biome argument
 * @param nether         dimension suffix flag
 * @param end            dimension suffix flag
 */
public record LocalPresenceTierSnapshot(TierKind kind, String localizedBiome, boolean nether, boolean end) {
}
