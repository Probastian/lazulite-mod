package de.lazuli.mainmenu;

import com.codedisaster.steamworks.SteamApps;
import com.codedisaster.steamworks.SteamFriends;
import com.codedisaster.steamworks.SteamFriendsCallback;
import com.codedisaster.steamworks.SteamInventory;
import com.codedisaster.steamworks.SteamInventoryCallback;
import com.codedisaster.steamworks.SteamInventoryResult;
import com.codedisaster.steamworks.SteamItemDetails;
import com.codedisaster.steamworks.SteamResult;

import de.lazuli.api.mainmenu.StoreItem;
import de.lazuli.features.mainmenu.services.OwnershipChecker;

import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The real, steamworks4j-fork-backed {@link OwnershipChecker} (spec FR5.2,
 * plan Decision 1): primary path checks a real Steamworks Inventory item
 * instance via {@link SteamInventory#getAllItems}, secondary/fallback path
 * checks a Steam DLC App ID via {@link SteamApps#isSubscribedApp}/
 * {@link SteamApps#isDlcInstalled}, and an item configured with neither
 * identifier is treated as not purchasable yet (never silently owned).
 *
 * <p>The sole {@code com.codedisaster.steamworks.SteamInventory*}-importing
 * class this feature introduces -- follows the same one-gateway-class, fail-
 * closed, never-throw, warn-sink discipline
 * {@code SteamworksSteamFriendsGateway} already establishes.
 *
 * <p>{@link SteamInventory#getAllItems}/{@link SteamInventory#getItemsByID}
 * are asynchronous (result delivered via {@link SteamInventoryCallback#onResultReady},
 * driven by the same {@code SteamAPI.runCallbacks()} pump the rest of this
 * mod's Steamworks integration already relies on) -- this class issues one
 * request per Store-panel open/refresh (spec FR5.5, "no live push assumed")
 * and caches the resolved owned-item-definition-id set until the next
 * {@link #refresh()} call, so {@link #isOwned} itself never blocks.
 */
public final class MainMenuStoreOwnershipChecker implements OwnershipChecker {

    private final Consumer<String> warnLogger;
    private final boolean steamAvailable;

    private SteamInventory steamInventory;
    private SteamApps steamApps;
    private SteamFriends steamFriends;
    private final SteamInventoryResult allItemsResult = new SteamInventoryResult();

    private volatile Set<Integer> ownedItemDefIds = Set.of();
    private volatile boolean resultPending;

    public MainMenuStoreOwnershipChecker(boolean steamAvailable, Consumer<String> warnLogger) {
        this.steamAvailable = steamAvailable;
        this.warnLogger = warnLogger;
        if (steamAvailable) {
            try {
                this.steamInventory = new SteamInventory(new Callback());
                this.steamApps = new SteamApps();
                this.steamFriends = new SteamFriends(new SteamFriendsCallback() { });
            } catch (RuntimeException e) {
                warn("Failed to initialize SteamInventory/SteamApps/SteamFriends (natives absent/incompatible platform?): " + e);
                this.steamInventory = null;
                this.steamApps = null;
                this.steamFriends = null;
            }
        }
        refresh();
    }

    private void warn(String message) {
        if (warnLogger != null) {
            warnLogger.accept(message);
        }
    }

    /** Re-issues the inventory-item request (spec FR5.5's "re-checked on next open"). Never blocks. */
    public void refresh() {
        if (steamInventory == null) {
            return;
        }
        try {
            resultPending = steamInventory.getAllItems(allItemsResult);
        } catch (RuntimeException e) {
            warn("SteamInventory.getAllItems failed: " + e);
            resultPending = false;
        }
    }

    @Override
    public boolean isOwned(StoreItem item) {
        OptionalInt inventoryItemDefId = item.inventoryItemDefId();
        if (inventoryItemDefId.isPresent()) {
            return steamInventory != null && ownedItemDefIds.contains(inventoryItemDefId.getAsInt());
        }
        OptionalInt dlcAppId = item.steamDlcAppId();
        if (dlcAppId.isPresent()) {
            return isDlcOwned(dlcAppId.getAsInt());
        }
        // Neither identifier configured -- not purchasable via Steam yet (spec FR5.2), never owned.
        return false;
    }

    /**
     * "Buy Now"/"Buy" primary action (spec FR5.3, plan Decision 1): for an
     * {@code inventoryItemDefId}-configured item, starts the fork's real
     * Steamworks Inventory Service purchase flow; for a {@code steamDlcAppId}
     * -only item, opens the Steam Overlay to that App ID's store page. A no-op
     * if the item has neither identifier configured (the "not available yet"
     * placeholder state) or if Steam/the natives are unavailable.
     */
    public void buy(StoreItem item) {
        try {
            if (item.inventoryItemDefId().isPresent() && steamInventory != null) {
                steamInventory.startPurchase(new int[]{item.inventoryItemDefId().getAsInt()}, new int[]{1});
            } else if (item.steamDlcAppId().isPresent() && steamFriends != null) {
                steamFriends.activateGameOverlayToStore(item.steamDlcAppId().getAsInt(), SteamFriends.OverlayToStoreFlag.None);
            }
        } catch (RuntimeException e) {
            warn("Failed to start purchase/open store overlay for \"" + item.id() + "\": " + e);
        }
    }

    private boolean isDlcOwned(int appId) {
        if (steamApps == null) {
            return false;
        }
        try {
            return steamApps.isSubscribedApp(appId) || steamApps.isDlcInstalled(appId);
        } catch (RuntimeException e) {
            warn("SteamApps DLC-ownership check failed for App ID " + appId + ": " + e);
            return false;
        }
    }

    private final class Callback implements SteamInventoryCallback {
        @Override
        public void onResultReady(SteamInventoryResult result, SteamResult status) {
            if (!result.equals(allItemsResult)) {
                return;
            }
            resultPending = false;
            try {
                if (status != SteamResult.OK) {
                    warn("SteamInventory result not OK: " + status);
                    return;
                }
                SteamItemDetails[] buffer = new SteamItemDetails[256];
                int count = steamInventory.getResultItems(result, buffer);
                Set<Integer> owned = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    owned.add(buffer[i].getDefinition());
                }
                ownedItemDefIds = owned;
            } catch (RuntimeException e) {
                warn("Failed to read SteamInventory result items: " + e);
            } finally {
                steamInventory.destroyResult(result);
            }
        }
    }
}
