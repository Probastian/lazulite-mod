package de.lazuli.features.richpresence.services;

import java.util.Optional;

/**
 * Pure, gateway-free mapping from each resolved {@link TierKind} to its
 * fixed Steamworks {@code steam_display} localization token name (Addendum
 * FR-RPD3). The companion token text lives on the Steamworks partner site
 * (App Admin -&gt; Rich Presence Localization, App ID 5052800) and, for local
 * reference/keep-in-sync purposes, in
 * {@code features/rich-presence/steamworks-localization-tokens.vdf}.
 *
 * <p>Implemented as an exhaustive {@code switch} expression over
 * {@link TierKind} rather than a {@code Map} with a default fallback, so a
 * future {@code TierKind} addition without a matching case here is a
 * <strong>compile error</strong>, not a silent runtime gap. This makes
 * {@link #tokenFor(TierKind)} total in practice for every real enum value;
 * the {@code Optional.empty()} "fail closed" path exists only for
 * defensive symmetry with the addendum's wording and is effectively
 * unreachable, since {@code switch} over an enum is exhaustive at compile
 * time and {@link TierKind} cannot gain an out-of-band value at runtime.
 */
public final class RichPresenceTokenMap {

    /**
     * @return the {@code steam_display} token name for the given tier
     *         (never empty for a real {@link TierKind} value -- every tier,
     *         including {@link TierKind#MAIN_MENU}, has a token per FR-RPD3)
     */
    public Optional<String> tokenFor(TierKind kind) {
        return switch (kind) {
            case MAIN_MENU -> Optional.of("#Status_MainMenu");
            case PAUSED -> Optional.of("#Status_Paused");
            case SPECTATING -> Optional.of("#Status_Spectating");
            case RIDING_MINECART -> Optional.of("#Status_RidingMinecart");
            case RIDING_BOAT -> Optional.of("#Status_RidingBoat");
            case NEAR_VILLAGE -> Optional.of("#Status_NearVillage");
            case EXPLORING -> Optional.of("#Status_Exploring");
            case STAYING -> Optional.of("#Status_Staying");
            case BUILDING -> Optional.of("#Status_Building");
            case DIGGING_AROUND -> Optional.of("#Status_DiggingAround");
        };
    }
}
