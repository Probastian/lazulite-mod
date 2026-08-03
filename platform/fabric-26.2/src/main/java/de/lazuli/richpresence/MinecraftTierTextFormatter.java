package de.lazuli.richpresence;

import de.lazuli.features.richpresence.services.PresenceTier;
import de.lazuli.features.richpresence.services.TierTextFormatter;

import net.minecraft.network.chat.Component;

/**
 * The sole {@code Text}/{@code Component}-touching class in this feature
 * (FR-RP3): turns a resolved {@link PresenceTier} into a fully localized
 * plain string via {@code Component.translatable(...).getString()},
 * composing the dimension suffix as an orthogonal modifier on top of
 * whichever base label wins (specification "Dimension suffix").
 */
public final class MinecraftTierTextFormatter implements TierTextFormatter {

    @Override
    public String format(PresenceTier tier) {
        String base = switch (tier.kind()) {
            case PAUSED -> Component.translatable("lazuli.presence.paused").getString();
            case SPECTATING -> Component.translatable("lazuli.presence.spectating").getString();
            case RIDING_MINECART -> Component.translatable("lazuli.presence.driving", biomeArg(tier)).getString();
            case RIDING_BOAT -> Component.translatable("lazuli.presence.sailing", biomeArg(tier)).getString();
            case NEAR_VILLAGE -> Component.translatable("lazuli.presence.near_village", biomeArg(tier)).getString();
            case EXPLORING -> Component.translatable("lazuli.presence.exploring", biomeArg(tier)).getString();
            case STAYING -> Component.translatable("lazuli.presence.staying", biomeArg(tier)).getString();
            case BUILDING -> Component.translatable("lazuli.presence.building", biomeArg(tier)).getString();
            case DIGGING_AROUND -> Component.translatable("lazuli.presence.digging_around", biomeArg(tier)).getString();
            case MAIN_MENU -> ""; // Never reached -- LocalPresenceTrackerImpl short-circuits this case.
        };

        if (tier.nether()) {
            return Component.translatable("lazuli.presence.dimension_suffix.nether", base).getString();
        }
        if (tier.end()) {
            return Component.translatable("lazuli.presence.dimension_suffix.end", base).getString();
        }
        return base;
    }

    private static Component biomeArg(PresenceTier tier) {
        return tier.biomeTranslationKey().map(Component::translatable).orElseGet(() -> Component.literal(""));
    }

    @Override
    public String localizeBiome(PresenceTier tier) {
        return tier.biomeTranslationKey().map(key -> Component.translatable(key).getString()).orElse("");
    }
}
