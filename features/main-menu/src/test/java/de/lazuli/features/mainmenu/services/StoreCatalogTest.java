package de.lazuli.features.mainmenu.services;

import de.lazuli.api.mainmenu.StoreItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StoreCatalogTest {

    private static StoreItem item(String id, String category, boolean featured, OptionalInt inventoryItemDefId, OptionalInt steamDlcAppId) {
        return new StoreItem(id, id + "-name", id + "-description", category,
                499, OptionalInt.empty(), inventoryItemDefId, steamDlcAppId, featured);
    }

    @Test
    void featuredItemReturnsTheSingleFeaturedItem() {
        StoreItem featured = item("moss-cloak", "TORSO", true, OptionalInt.empty(), OptionalInt.empty());
        StoreItem notFeatured = item("wanderer-hood", "HEAD", false, OptionalInt.empty(), OptionalInt.empty());
        StoreCatalog catalog = new StoreCatalog(List.of(featured, notFeatured), i -> false);

        assertThat(catalog.featuredItem()).contains(featured);
    }

    @Test
    void featuredItemIsEmptyWhenNoItemIsFeatured() {
        StoreItem a = item("moss-cloak", "TORSO", false, OptionalInt.empty(), OptionalInt.empty());
        StoreItem b = item("wanderer-hood", "HEAD", false, OptionalInt.empty(), OptionalInt.empty());
        StoreCatalog catalog = new StoreCatalog(List.of(a, b), i -> false);

        assertThat(catalog.featuredItem()).isEmpty();
    }

    @Test
    void featuredItemReturnsTheFirstMatchWhenMultipleAreFeatured() {
        StoreItem first = item("moss-cloak", "TORSO", true, OptionalInt.empty(), OptionalInt.empty());
        StoreItem second = item("wanderer-hood", "HEAD", true, OptionalInt.empty(), OptionalInt.empty());
        StoreCatalog catalog = new StoreCatalog(List.of(first, second), i -> false);

        assertThat(catalog.featuredItem()).contains(first);
    }

    @Test
    void itemsByCategoryGroupsByCategoryPreservingOrder() {
        StoreItem cloak = item("moss-cloak", "TORSO", true, OptionalInt.empty(), OptionalInt.empty());
        StoreItem hood = item("wanderer-hood", "HEAD", false, OptionalInt.empty(), OptionalInt.empty());
        StoreItem otherTorso = item("second-torso-item", "TORSO", false, OptionalInt.empty(), OptionalInt.empty());
        StoreCatalog catalog = new StoreCatalog(List.of(cloak, hood, otherTorso), i -> false);

        Map<String, List<StoreItem>> grouped = catalog.itemsByCategory();

        assertThat(grouped.keySet()).containsExactly("TORSO", "HEAD");
        assertThat(grouped.get("TORSO")).containsExactly(cloak, otherTorso);
        assertThat(grouped.get("HEAD")).containsExactly(hood);
    }

    @Test
    void isOwnedDelegatesToTheInjectedOwnershipChecker() {
        StoreItem owned = item("owned-item", "TORSO", false, OptionalInt.of(1), OptionalInt.empty());
        StoreItem notOwned = item("not-owned-item", "TORSO", false, OptionalInt.of(2), OptionalInt.empty());
        StoreItem noIdentifier = item("no-identifier-item", "TORSO", false, OptionalInt.empty(), OptionalInt.empty());

        Set<String> ownedIds = Set.of(owned.id());
        StoreCatalog catalog = new StoreCatalog(
                List.of(owned, notOwned, noIdentifier),
                candidate -> ownedIds.contains(candidate.id()));

        assertThat(catalog.isOwned(owned)).isTrue();
        assertThat(catalog.isOwned(notOwned)).isFalse();
        assertThat(catalog.isOwned(noIdentifier)).isFalse();
    }
}
