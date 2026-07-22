package de.lazuli.features.richpresence.services;

import java.util.Optional;

/**
 * A resolved status tier plus whichever arguments its lang key needs (spec
 * FR-RP3). Never itself a translated {@code String} -- producing one needs
 * {@code Text}/{@code Component}, a platform concern; see
 * {@link TierTextFormatter}.
 *
 * @param kind                the resolved tier
 * @param biomeTranslationKey the biome argument, present only for
 *                            biome-bearing tiers (riding/near-village/
 *                            movement-derived)
 * @param nether              dimension suffix flag (orthogonal modifier, see
 *                            specification "Dimension suffix")
 * @param end                 dimension suffix flag
 */
public record PresenceTier(TierKind kind, Optional<String> biomeTranslationKey, boolean nether, boolean end) {

    /** A tier with no biome argument and no dimension suffix (e.g. Paused, Spectating, Digging Around). */
    public static PresenceTier of(TierKind kind) {
        return new PresenceTier(kind, Optional.empty(), false, false);
    }

    /** A biome-bearing tier, carrying the dimension suffix flags from the current signals. */
    public static PresenceTier biomeBearing(TierKind kind, PresenceSignals signals) {
        String key = signals.biomeTranslationKey();
        Optional<String> biome = (key == null || key.isEmpty()) ? Optional.empty() : Optional.of(key);
        return new PresenceTier(kind, biome, signals.nether(), signals.end());
    }
}
