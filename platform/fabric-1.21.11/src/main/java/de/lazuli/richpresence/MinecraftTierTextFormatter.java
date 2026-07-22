package de.lazuli.richpresence;

import de.lazuli.features.richpresence.services.PresenceTier;
import de.lazuli.features.richpresence.services.TierTextFormatter;

import net.minecraft.text.Text;

/**
 * The sole {@code Text}-touching class in this feature (FR-RP3): turns a
 * resolved {@link PresenceTier} into a fully localized plain string via
 * {@code Text.translatable(...).getString()}, composing the dimension
 * suffix as an orthogonal modifier on top of whichever base label wins
 * (specification "Dimension suffix").
 */
public final class MinecraftTierTextFormatter implements TierTextFormatter {

    @Override
    public String format(PresenceTier tier) {
        String base = switch (tier.kind()) {
            case PAUSED -> Text.translatable("lazuli.presence.paused").getString();
            case SPECTATING -> Text.translatable("lazuli.presence.spectating").getString();
            case RIDING_MINECART -> Text.translatable("lazuli.presence.driving", biomeArg(tier)).getString();
            case RIDING_BOAT -> Text.translatable("lazuli.presence.sailing", biomeArg(tier)).getString();
            case NEAR_VILLAGE -> Text.translatable("lazuli.presence.near_village", biomeArg(tier)).getString();
            case EXPLORING -> Text.translatable("lazuli.presence.exploring", biomeArg(tier)).getString();
            case STAYING -> Text.translatable("lazuli.presence.staying", biomeArg(tier)).getString();
            case BUILDING -> Text.translatable("lazuli.presence.building", biomeArg(tier)).getString();
            case DIGGING_AROUND -> Text.translatable("lazuli.presence.digging_around").getString();
            case MAIN_MENU -> ""; // Never reached -- LocalPresenceTrackerImpl short-circuits this case.
        };

        if (tier.nether()) {
            return Text.translatable("lazuli.presence.dimension_suffix.nether", base).getString();
        }
        if (tier.end()) {
            return Text.translatable("lazuli.presence.dimension_suffix.end", base).getString();
        }
        return base;
    }

    private static Text biomeArg(PresenceTier tier) {
        return tier.biomeTranslationKey().map(Text::translatable).orElseGet(() -> Text.literal(""));
    }
}
