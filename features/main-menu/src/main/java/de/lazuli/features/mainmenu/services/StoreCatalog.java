package de.lazuli.features.mainmenu.services;

import de.lazuli.api.mainmenu.StoreItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads/holds the config-driven Store/Wardrobe catalog (spec FR5.1) and
 * exposes plain-JVM-testable featured-item selection, category grouping, and
 * per-item ownership lookup delegating to an injected {@link OwnershipChecker}
 * (spec Public API item 2). Zero {@code net.minecraft.*}/steamworks4j import.
 *
 * <p>Usage example:
 * <pre>{@code
 * StoreCatalog catalog = new StoreCatalog(items, ownershipChecker);
 * Optional<StoreItem> featured = catalog.featuredItem();
 * Map<String, List<StoreItem>> byCategory = catalog.itemsByCategory();
 * boolean owned = catalog.isOwned(someItem);
 * }</pre>
 */
public final class StoreCatalog {

    private final List<StoreItem> items;
    private final OwnershipChecker ownershipChecker;

    /**
     * @param items            the catalog's items, in configured order; not
     *                         copied defensively beyond {@link List#copyOf}
     * @param ownershipChecker the injected ownership-lookup seam (Decision 1);
     *                         implemented platform-side against the real
     *                         Steamworks Inventory API, with a DLC fallback
     */
    public StoreCatalog(List<StoreItem> items, OwnershipChecker ownershipChecker) {
        this.items = List.copyOf(items);
        this.ownershipChecker = ownershipChecker;
    }

    /** @return every catalog item, in configured order. */
    public List<StoreItem> items() {
        return items;
    }

    /**
     * Selects the Store panel's single featured/hero item (spec FR5.1).
     *
     * <p>Documented choice for the ambiguous cases the spec doesn't pin: if
     * exactly one item has {@code featured() == true}, that item is returned.
     * If <strong>zero</strong> items are featured, this returns
     * {@link Optional#empty()} (no hero item renders -- the honest reflection
     * of an empty/misconfigured catalog, rather than silently promoting an
     * arbitrary item to "featured"). If <strong>more than one</strong> item
     * is marked featured (a config-authoring mistake this class does not
     * itself validate against), the first one encountered in catalog order
     * wins -- deterministic and simple, not asserted to be "correct" beyond
     * that.
     *
     * @return the featured item, or empty per the above
     */
    public Optional<StoreItem> featuredItem() {
        return items.stream().filter(StoreItem::featured).findFirst();
    }

    /**
     * Groups every catalog item by {@link StoreItem#category()} (spec FR5.1's
     * "All Cosmetics" grid), preserving first-seen category order and each
     * category's own item order.
     *
     * @return an unmodifiable, insertion-ordered map of category to its items
     */
    public Map<String, List<StoreItem>> itemsByCategory() {
        Map<String, List<StoreItem>> grouped = new LinkedHashMap<>();
        for (StoreItem item : items) {
            grouped.computeIfAbsent(item.category(), k -> new java.util.ArrayList<>()).add(item);
        }
        Map<String, List<StoreItem>> unmodifiable = new LinkedHashMap<>();
        grouped.forEach((category, categoryItems) -> unmodifiable.put(category, List.copyOf(categoryItems)));
        // Map.copyOf does not preserve insertion order -- this grouping's own
        // first-seen-category ordering is load-bearing (this method's own
        // Javadoc), so an explicit unmodifiable wrapper is used instead.
        return java.util.Collections.unmodifiableMap(unmodifiable);
    }

    /**
     * @param item the item to check
     * @return the injected {@link OwnershipChecker}'s result for {@code item}
     */
    public boolean isOwned(StoreItem item) {
        return ownershipChecker.isOwned(item);
    }
}
