package de.lazuli.features.mainmenu.services;

import de.lazuli.api.mainmenu.MainMenuTab;
import de.lazuli.api.mainmenu.WardrobeSlot;

import java.util.HashMap;
import java.util.Map;

/**
 * Plain-JVM-testable, mutable holder for every piece of {@code MainMenuScreen}
 * transient (session-only, spec FR1.3) UI state -- active tab, expanded
 * world/server row, Servers sub-view, wardrobe active slot/equip map, and
 * modal-visibility flags. Zero {@code net.minecraft.*}/steamworks4j import.
 *
 * <p>A fresh instance always starts in the "nothing selected" default state
 * (spec FR1.3); {@code MainMenuScreen} constructs a new instance every time
 * it is itself (re)constructed, rather than resetting an existing one, so
 * this class does not need its own explicit {@code reset()} method.
 *
 * <p>Usage example:
 * <pre>{@code
 * MainMenuStateMachine state = new MainMenuStateMachine();
 * state.selectTab(MainMenuTab.WORLDS); // opens the Worlds panel
 * state.selectTab(MainMenuTab.WORLDS); // clicking the same tab again closes it
 * }</pre>
 */
public final class MainMenuStateMachine {

    /** The four sub-views the Servers panel's header toggle switches between (spec FR4.1). */
    public enum ServersSubView {
        SAVED,
        BROWSER
    }

    private MainMenuTab activeTab;
    private String expandedRowId;
    private ServersSubView serversSubView = ServersSubView.SAVED;
    private WardrobeSlot activeWardrobeSlot = WardrobeSlot.HEAD;
    private final Map<WardrobeSlot, String> equipped = new HashMap<>();
    private boolean directConnectModalOpen;
    private boolean addServerModalOpen;
    private boolean worldCreateToastVisible;

    /** Default constructor: no tab active (spec FR1.3). */
    public MainMenuStateMachine() {
        this(null);
    }

    /**
     * Seeds the initial active tab at construction (batch-2-fixes FR-F2.2),
     * used by {@code MainMenuScreen} to open with {@code MainMenuTab.HOME}
     * already selected. All other {@link #selectTab} toggle semantics are
     * unaffected once construction is done.
     *
     * @param initialTab the tab to start active, or {@code null} for the
     *                    default "nothing selected" state
     */
    public MainMenuStateMachine(MainMenuTab initialTab) {
        this.activeTab = initialTab;
    }

    /** @return the currently active tab, or {@code null} if no tab is active (spec FR1.4). */
    public MainMenuTab activeTab() {
        return activeTab;
    }

    /**
     * Applies the tab-bar click behavior (spec FR2.2): clicking a tab that is
     * not currently active selects it; clicking the already-active tab
     * deselects it (closing the panel back to fully transparent); clicking a
     * different tab while one is active switches directly.
     *
     * @param clicked the tab that was clicked; must not be {@code null}
     */
    public void selectTab(MainMenuTab clicked) {
        if (clicked == null) {
            throw new IllegalArgumentException("clicked must not be null");
        }
        activeTab = (activeTab == clicked) ? null : clicked;
    }

    /** @return the currently expanded world/server row's id, or {@code null} if none is expanded. */
    public String expandedRowId() {
        return expandedRowId;
    }

    /**
     * Single-expand (accordion) toggle for Worlds/Servers-Saved rows (spec
     * FR3.3/FR4.2): clicking the already-expanded row's id collapses it;
     * clicking a different row's id collapses any previously-expanded row and
     * expands the clicked one.
     *
     * @param rowId the clicked row's stable identifier; must not be {@code null}
     */
    public void toggleRowExpanded(String rowId) {
        if (rowId == null) {
            throw new IllegalArgumentException("rowId must not be null");
        }
        expandedRowId = rowId.equals(expandedRowId) ? null : rowId;
    }

    /** @return the Servers panel's currently active sub-view (spec FR4.1). */
    public ServersSubView serversSubView() {
        return serversSubView;
    }

    /** Switches the Servers panel's Saved/Browser sub-view (spec FR4.1). */
    public void setServersSubView(ServersSubView subView) {
        if (subView == null) {
            throw new IllegalArgumentException("subView must not be null");
        }
        serversSubView = subView;
    }

    /** @return the Wardrobe panel's currently active slot (spec FR6.1). */
    public WardrobeSlot activeWardrobeSlot() {
        return activeWardrobeSlot;
    }

    /** Switches which slot's item grid the Wardrobe panel shows (spec FR6.1). */
    public void selectWardrobeSlot(WardrobeSlot slot) {
        if (slot == null) {
            throw new IllegalArgumentException("slot must not be null");
        }
        activeWardrobeSlot = slot;
    }

    /**
     * Equips {@code itemId} into {@code slot}, replacing any prior equip for
     * that slot (spec FR6.2).
     *
     * @param slot   the slot to equip into; must not be {@code null}
     * @param itemId the catalog item id to equip; must not be {@code null}
     */
    public void equip(WardrobeSlot slot, String itemId) {
        if (slot == null) {
            throw new IllegalArgumentException("slot must not be null");
        }
        if (itemId == null) {
            throw new IllegalArgumentException("itemId must not be null");
        }
        equipped.put(slot, itemId);
    }

    /** @return the catalog item id equipped in {@code slot}, or {@code null} if unequipped. */
    public String equippedItemId(WardrobeSlot slot) {
        return equipped.get(slot);
    }

    /** @return an immutable snapshot of every slot's current equip state. */
    public Map<WardrobeSlot, String> equipSnapshot() {
        return Map.copyOf(equipped);
    }

    /**
     * Seeds the equip map from persisted state (e.g. at screen construction,
     * from {@code WardrobeConfigIO}) without going through {@link #equip}'s
     * one-slot-at-a-time semantics.
     */
    public void loadEquipped(Map<WardrobeSlot, String> initial) {
        equipped.clear();
        if (initial != null) {
            equipped.putAll(initial);
        }
    }

    /** @return whether the Direct Connect modal is currently open (spec FR4.3). */
    public boolean isDirectConnectModalOpen() {
        return directConnectModalOpen;
    }

    public void openDirectConnectModal() {
        directConnectModalOpen = true;
    }

    public void closeDirectConnectModal() {
        directConnectModalOpen = false;
    }

    /** @return whether the Add Server modal is currently open (spec FR4.3). */
    public boolean isAddServerModalOpen() {
        return addServerModalOpen;
    }

    public void openAddServerModal() {
        addServerModalOpen = true;
    }

    public void closeAddServerModal() {
        addServerModalOpen = false;
    }

    /** @return whether the (optional, non-required) world-create transitional toast is visible (spec FR3.5). */
    public boolean isWorldCreateToastVisible() {
        return worldCreateToastVisible;
    }

    public void showWorldCreateToast() {
        worldCreateToastVisible = true;
    }

    public void hideWorldCreateToast() {
        worldCreateToastVisible = false;
    }
}
