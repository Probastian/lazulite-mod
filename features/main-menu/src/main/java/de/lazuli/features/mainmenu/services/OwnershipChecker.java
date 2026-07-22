package de.lazuli.features.mainmenu.services;

import de.lazuli.api.mainmenu.StoreItem;

/**
 * Ownership-lookup seam (spec Public API item 2, plan Decision 1) implemented
 * platform-side against the real Steamworks Inventory API (primary path) with
 * a Steam DLC App ID fallback -- kept as an interface here so
 * {@link StoreCatalog}'s own filtering/grouping logic stays plain-JVM-testable
 * against a fake implementation.
 */
@FunctionalInterface
public interface OwnershipChecker {

    /**
     * @param item the catalog item to check
     * @return {@code true} if the current Steam user owns {@code item}
     */
    boolean isOwned(StoreItem item);
}
