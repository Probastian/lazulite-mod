package de.lazuli.features.richpresence.services;

/**
 * The sole seam platform code implements to turn a resolved
 * {@link PresenceTier} into a fully localized, plain {@code String}, via
 * {@code Text.translatable(...).getString()} (Yarn) /
 * {@code Component.translatable(...).getString()} (Mojang mapping) -- FR-RP3.
 * Keeps {@link LocalPresenceTrackerImpl} itself free of
 * {@code net.minecraft.*} import.
 */
@FunctionalInterface
public interface TierTextFormatter {

    /**
     * @param tier a non-{@code MAIN_MENU} tier (the tracker never hands this
     *             formatter a Main Menu tier -- FR-RP7 short-circuits that
     *             case to {@code Optional.empty()} before ever calling this)
     * @return the fully localized plain status string
     */
    String format(PresenceTier tier);
}
