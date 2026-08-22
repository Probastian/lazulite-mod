package de.lazuli.tweaks;

import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the Minecraft-type-free sub-algorithms of {@link LootBinGrouping}
 * (docs/specs/tweaks-loot-bin-ui.md R6/R7/R12/R16) -- written once, here,
 * even though {@code LootBinGrouping} itself is duplicated across all three
 * platform {@code src/main} trees, since this tested slice has no Yarn/
 * Mojmap-specific surface (matches the {@code
 * CrossWorldStatsOfflineBucketFilter}/{@code
 * CrossWorldStatsOfflineBucketFilterTest} precedent, plan's Test Strategy).
 *
 * <p>Not covered here (genuinely Minecraft-typed, manual/in-game-only per
 * the plan): R5's real {@code ItemStack} component-equality behavior, R4's
 * creative-tab reverse-index construction, R9's player-vs-container slot
 * classification, R13's actual click dispatch, R10's tooltip-line
 * computation.
 */
class LootBinGroupingLogicTest {

    // ---- R12: chooseSlot -------------------------------------------------

    @Test
    void chooseSlot_singleCandidate_returnsItsIndex() {
        int result = LootBinGrouping.chooseSlot(List.of(new LootBinGrouping.SlotCandidate(5, 32)));

        assertThat(result).isEqualTo(5);
    }

    @Test
    void chooseSlot_pickesLargestQuantity() {
        List<LootBinGrouping.SlotCandidate> candidates = List.of(
                new LootBinGrouping.SlotCandidate(0, 10),
                new LootBinGrouping.SlotCandidate(1, 64),
                new LootBinGrouping.SlotCandidate(2, 20));

        int result = LootBinGrouping.chooseSlot(candidates);

        assertThat(result).isEqualTo(1);
    }

    @Test
    void chooseSlot_tieBrokenByLowestSlotIndex() {
        List<LootBinGrouping.SlotCandidate> candidates = List.of(
                new LootBinGrouping.SlotCandidate(7, 32),
                new LootBinGrouping.SlotCandidate(2, 32),
                new LootBinGrouping.SlotCandidate(4, 32));

        int result = LootBinGrouping.chooseSlot(candidates);

        assertThat(result).isEqualTo(2);
    }

    @Test
    void chooseSlot_emptyList_returnsNegativeOne() {
        int result = LootBinGrouping.chooseSlot(List.of());

        assertThat(result).isEqualTo(-1);
    }

    // ---- R7: comparatorFor -------------------------------------------------

    @Test
    void comparatorFor_countDesc_sortsHighestFirst_tieBrokenAlphabetically() {
        List<LootBinGrouping.DisplayEntry> entries = new java.util.ArrayList<>(List.of(
                new LootBinGrouping.DisplayEntry("Dirt", 5),
                new LootBinGrouping.DisplayEntry("Stone", 64),
                new LootBinGrouping.DisplayEntry("Andesite", 64)));

        Comparator<LootBinGrouping.DisplayEntry> comparator = LootBinGrouping.comparatorFor("COUNT_DESC");
        assertThat(comparator).isNotNull();
        entries.sort(comparator);

        assertThat(entries).extracting(LootBinGrouping.DisplayEntry::displayName)
                .containsExactly("Andesite", "Stone", "Dirt");
    }

    @Test
    void comparatorFor_alphabetical_sortsCaseInsensitively() {
        List<LootBinGrouping.DisplayEntry> entries = new java.util.ArrayList<>(List.of(
                new LootBinGrouping.DisplayEntry("stone", 1),
                new LootBinGrouping.DisplayEntry("Andesite", 1),
                new LootBinGrouping.DisplayEntry("dirt", 1)));

        Comparator<LootBinGrouping.DisplayEntry> comparator = LootBinGrouping.comparatorFor("ALPHABETICAL");
        assertThat(comparator).isNotNull();
        entries.sort(comparator);

        assertThat(entries).extracting(LootBinGrouping.DisplayEntry::displayName)
                .containsExactly("Andesite", "dirt", "stone");
    }

    @Test
    void comparatorFor_creativeOrderOrUnrecognized_returnsNull() {
        assertThat(LootBinGrouping.comparatorFor("CREATIVE_ORDER")).isNull();
        assertThat(LootBinGrouping.comparatorFor("SOME_FUTURE_MODE")).isNull();
    }

    // ---- R6: aggregate -----------------------------------------------------

    @Test
    void aggregate_sumsByKey_preservingFirstSeenOrder() {
        List<Map.Entry<String, Integer>> raw = List.of(
                new AbstractMap.SimpleEntry<>("stick", 12),
                new AbstractMap.SimpleEntry<>("dirt", 64),
                new AbstractMap.SimpleEntry<>("stick", 3));

        Map<String, Integer> result = LootBinGrouping.aggregate(raw);

        assertThat(result).containsExactly(
                Map.entry("stick", 15),
                Map.entry("dirt", 64));
    }

    // ---- R16: fullnessText ---------------------------------------------------

    @Test
    void fullnessText_formatsOccupiedOverTotal() {
        assertThat(LootBinGrouping.fullnessText(42, 72)).isEqualTo("42/72 slots");
        assertThat(LootBinGrouping.fullnessText(0, 27)).isEqualTo("0/27 slots");
    }
}
