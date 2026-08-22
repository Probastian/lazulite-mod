package de.lazuli.tweaks;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loot Bin UI (docs/specs/tweaks-loot-bin-ui.md) grouping/aggregation logic
 * for the 26.2 Mojmap platform (byte-identical to the 26.1 copy of this
 * file -- confirmed identical API surface via {@code javap} against both
 * modules' own resolved Minecraft jars, see plan's mandatory javap pass).
 *
 * <p>Split into two sections: a Minecraft-type-free "pure logic" section
 * (R6/R7/R12/R16's sub-algorithms, unit tested once in this module's own
 * test tree per the plan's Test Strategy -- {@code
 * LootBinGroupingLogicTest}, following the {@code
 * CrossWorldStatsOfflineBucketFilter} precedent) and a Minecraft-typed
 * grouping section (R4/R5/R9, manual/in-game verification only).
 */
public final class LootBinGrouping {

    private LootBinGrouping() {
    }

    // ---------------------------------------------------------------
    // Pure, Minecraft-type-free sub-algorithms.
    // ---------------------------------------------------------------

    /** One real candidate slot for R12's slot-resolution strategy. */
    public record SlotCandidate(int slotIndex, int quantity) {
    }

    /**
     * R12: largest quantity wins; ties broken by lowest slot index. Returns
     * -1 for an empty candidate list (defensive; not reachable in practice
     * since a resolvable click always has at least one backing slot).
     */
    public static int chooseSlot(List<SlotCandidate> candidates) {
        int bestSlot = -1;
        int bestQuantity = Integer.MIN_VALUE;
        for (SlotCandidate candidate : candidates) {
            if (candidate.quantity() > bestQuantity
                    || (candidate.quantity() == bestQuantity && candidate.slotIndex() < bestSlot)) {
                bestQuantity = candidate.quantity();
                bestSlot = candidate.slotIndex();
            }
        }
        return bestSlot;
    }

    /** Stand-in for one displayed aggregated row, used by {@link #comparatorFor(String)}. */
    public record DisplayEntry(String displayName, long totalCount) {
    }

    /**
     * R7's {@code sortWithinGroup} comparator for the two pure modes
     * ({@code COUNT_DESC}/{@code ALPHABETICAL}). Returns {@code null} for
     * {@code CREATIVE_ORDER} (and any unrecognized value) since that mode
     * depends on vanilla's own creative-tab item order, not testable this
     * way -- callers fall back to creative-index sorting in that case.
     */
    public static Comparator<DisplayEntry> comparatorFor(String sortMode) {
        if ("COUNT_DESC".equals(sortMode)) {
            return Comparator.comparingLong(DisplayEntry::totalCount).reversed()
                    .thenComparing(e -> e.displayName().toLowerCase(Locale.ROOT));
        }
        if ("ALPHABETICAL".equals(sortMode)) {
            return Comparator.comparing(e -> e.displayName().toLowerCase(Locale.ROOT));
        }
        return null;
    }

    /** R6's "sum across every matching real slot," abstracted over a generic key. */
    public static <K> Map<K, Integer> aggregate(List<Map.Entry<K, Integer>> rawSlots) {
        Map<K, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<K, Integer> entry : rawSlots) {
            result.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        return result;
    }

    /** R16's fullness-indicator text, e.g. {@code "42/72 slots"}. */
    public static String fullnessText(int occupied, int total) {
        return occupied + "/" + total + " slots";
    }

    // ---------------------------------------------------------------
    // Minecraft-typed grouping (R4-R7, R9, R16) -- manual/in-game-only,
    // per the plan's Test Strategy.
    // ---------------------------------------------------------------

    private static Map<Item, CreativeModeTab> reverseIndex;
    private static List<CreativeModeTab> tabOrder;

    /** R4: cached, lazily built, invalidated on resource/data reload. */
    public static synchronized Map<Item, CreativeModeTab> reverseIndex() {
        if (reverseIndex == null) {
            buildReverseIndex();
        }
        return reverseIndex;
    }

    public static synchronized List<CreativeModeTab> tabOrder() {
        if (tabOrder == null) {
            buildReverseIndex();
        }
        return tabOrder;
    }

    /** R4: invalidate on resource/data reload. */
    public static synchronized void invalidate() {
        reverseIndex = null;
        tabOrder = null;
    }

    private static void buildReverseIndex() {
        Map<Item, CreativeModeTab> index = new HashMap<>();
        List<CreativeModeTab> order = CreativeModeTabs.tabs();
        for (CreativeModeTab tab : order) {
            for (ItemStack stack : tab.getDisplayItems()) {
                index.putIfAbsent(stack.getItem(), tab);
            }
        }
        reverseIndex = index;
        tabOrder = order;
    }

    /** R9: is this real Slot backed by the viewing player's own inventory? */
    public static boolean isPlayerSlot(Slot slot, Inventory playerInventory) {
        return slot.container == playerInventory;
    }

    /** Top/bottom (relative, unshifted) screen-space bounds of the player-inventory slots. */
    public record PlayerInvBounds(int top, int bottom) {
    }

    /** Scans the handler's own slot list for the player-inventory region's real (baked-in) bounds. */
    public static PlayerInvBounds playerInvBounds(List<Slot> slots, Inventory playerInventory) {
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (Slot slot : slots) {
            if (isPlayerSlot(slot, playerInventory)) {
                top = Math.min(top, slot.y);
                bottom = Math.max(bottom, slot.y + 18);
            }
        }
        if (top == Integer.MAX_VALUE) {
            return new PlayerInvBounds(84, 142);
        }
        return new PlayerInvBounds(top, bottom);
    }

    /** One real backing slot+stack pair contributing to an aggregated entry (R6). */
    public record RealSlot(Slot slot, ItemStack stack) {
    }

    /** R5's distinct-entry key: item + exact component data, ignoring count. */
    public static final class EntryKey {
        private final ItemStack representative;

        public EntryKey(ItemStack representative) {
            this.representative = representative;
        }

        public ItemStack representative() {
            return representative;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof EntryKey other
                    && ItemStack.isSameItemSameComponents(representative, other.representative);
        }

        @Override
        public int hashCode() {
            return representative.getItem().hashCode();
        }
    }

    /** One aggregated, displayed row (R5-R7). */
    public record AggregatedEntry(EntryKey key, long totalCount, List<RealSlot> backingSlots) {
        public int stackCount() {
            return backingSlots.size();
        }
    }

    /** One non-empty group (creative tab) and its sorted entries (R4/R6/R7). */
    public record GroupResult(CreativeModeTab tab, List<AggregatedEntry> entries) {
    }

    /**
     * R4-R7's per-render aggregation pass over the container's own slots
     * (R9's container-vs-player split). Recomputed fresh every call (R6/R14
     * "poll current state, no staleness" -- callers must invoke this once
     * per render pass, never cache the result across frames).
     */
    public static List<GroupResult> computeGroups(List<Slot> slots, Inventory playerInventory,
            String groupOrder, String sortWithinGroup) {
        Map<EntryKey, List<RealSlot>> byKey = new LinkedHashMap<>();
        for (Slot slot : slots) {
            if (isPlayerSlot(slot, playerInventory)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            byKey.computeIfAbsent(new EntryKey(stack), k -> new ArrayList<>()).add(new RealSlot(slot, stack));
        }

        Map<Item, CreativeModeTab> index = reverseIndex();
        Map<CreativeModeTab, List<AggregatedEntry>> byTab = new LinkedHashMap<>();
        for (Map.Entry<EntryKey, List<RealSlot>> entry : byKey.entrySet()) {
            Item item = entry.getKey().representative().getItem();
            CreativeModeTab tab = index.get(item);
            if (tab == null) {
                continue; // not present in any creative tab -- nothing to group it under
            }
            long total = 0;
            for (RealSlot real : entry.getValue()) {
                total += real.stack().getCount();
            }
            byTab.computeIfAbsent(tab, t -> new ArrayList<>())
                    .add(new AggregatedEntry(entry.getKey(), total, entry.getValue()));
        }

        for (Map.Entry<CreativeModeTab, List<AggregatedEntry>> e : byTab.entrySet()) {
            sortEntries(e.getValue(), e.getKey(), sortWithinGroup);
        }

        List<CreativeModeTab> orderedTabs = new ArrayList<>(byTab.keySet());
        if ("ALPHABETICAL".equals(groupOrder)) {
            orderedTabs.sort(Comparator.comparing(t -> t.getDisplayName().getString().toLowerCase(Locale.ROOT)));
        } else {
            List<CreativeModeTab> natural = tabOrder();
            orderedTabs.sort(Comparator.comparingInt(natural::indexOf));
        }

        List<GroupResult> results = new ArrayList<>();
        for (CreativeModeTab tab : orderedTabs) {
            results.add(new GroupResult(tab, byTab.get(tab)));
        }
        return results;
    }

    private static void sortEntries(List<AggregatedEntry> entries, CreativeModeTab tab, String sortWithinGroup) {
        Comparator<DisplayEntry> pure = comparatorFor(sortWithinGroup);
        if (pure == null) {
            Map<Item, Integer> creativeIndex = new HashMap<>();
            int i = 0;
            for (ItemStack stack : tab.getDisplayItems()) {
                creativeIndex.putIfAbsent(stack.getItem(), i++);
            }
            entries.sort(Comparator.comparingInt(
                    e -> creativeIndex.getOrDefault(e.key().representative().getItem(), Integer.MAX_VALUE)));
            return;
        }
        entries.sort((a, b) -> pure.compare(toDisplayEntry(a), toDisplayEntry(b)));
    }

    private static DisplayEntry toDisplayEntry(AggregatedEntry entry) {
        return new DisplayEntry(displayName(entry.key().representative()), entry.totalCount());
    }

    public static String displayName(ItemStack stack) {
        return stack.getHoverName().getString();
    }

    public static Component displayNameComponent(ItemStack stack) {
        return stack.getHoverName();
    }

    /** R16: occupied vs. total real slot count over the container's own slot range only. */
    public static int[] containerFullness(List<Slot> slots, Inventory playerInventory) {
        int occupied = 0;
        int total = 0;
        for (Slot slot : slots) {
            if (isPlayerSlot(slot, playerInventory)) {
                continue;
            }
            total++;
            if (!slot.getItem().isEmpty()) {
                occupied++;
            }
        }
        return new int[] {occupied, total};
    }
}
