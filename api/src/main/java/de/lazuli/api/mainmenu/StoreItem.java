package de.lazuli.api.mainmenu;

import java.util.OptionalInt;

/**
 * One purchasable/equippable entry in the Store/Wardrobe catalog (spec
 * FR5.1/FR6.2), config-driven via {@code StoreCatalogConfigIO}.
 *
 * <p>Per the implementation plan's Decision 1 (updated from the
 * specification's original single-{@code steamDlcAppId} schema): ownership
 * may be resolved either via a real Steamworks Inventory item-definition ID
 * ({@link #inventoryItemDefId()}, the primary path) or a Steam DLC App ID
 * ({@link #steamDlcAppId()}, a secondary fallback for items shipped as whole
 * DLC bundles). An item configured with neither identifier (both
 * {@link OptionalInt#empty()}) is the expected dev-time default -- it is
 * treated as not purchasable via Steam yet (spec FR5.2), never as silently
 * owned.
 *
 * @param id                 stable catalog identifier, e.g. {@code "moss-cloak"}
 * @param displayName        human-readable name shown in the Store/Wardrobe UI
 * @param description        short flavor/description text
 * @param category           groups items for the Store's "All Cosmetics" grid
 *                           and the Wardrobe's per-slot filtering; expected to
 *                           match a {@link WardrobeSlot} name for
 *                           wardrobe-eligible items (spec FR6.2)
 * @param priceCents         display price, in whole cents
 * @param originalPriceCents pre-discount price, in whole cents, for a
 *                           strikethrough display; empty means no discount
 * @param inventoryItemDefId the real Steamworks Inventory item-definition ID
 *                           (primary ownership path, plan Decision 1); empty
 *                           means this item has no inventory item configured
 * @param steamDlcAppId      the Steam DLC App ID (secondary/fallback
 *                           ownership path, plan Decision 1); empty means
 *                           this item has no DLC App ID configured
 * @param featured           whether this item is the Store panel's single
 *                           featured/hero item
 */
public record StoreItem(String id, String displayName, String description, String category,
                         int priceCents, OptionalInt originalPriceCents,
                         OptionalInt inventoryItemDefId, OptionalInt steamDlcAppId,
                         boolean featured) {
}
