package de.lazuli.features.mainmenu.services;

import de.lazuli.api.mainmenu.MainMenuTab;
import de.lazuli.api.mainmenu.WardrobeSlot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MainMenuStateMachineTest {

    private final MainMenuStateMachine state = new MainMenuStateMachine();

    @Test
    void freshInstanceHasNoTabActive() {
        assertThat(state.activeTab()).isNull();
    }

    @Test
    void seededInitialTabConstructorStartsWithThatTabActive() {
        // Batch-2-fixes FR-F2.2: MainMenuScreen seeds MainMenuTab.HOME so it
        // opens already-selected, without a click.
        MainMenuStateMachine seeded = new MainMenuStateMachine(MainMenuTab.HOME);
        assertThat(seeded.activeTab()).isEqualTo(MainMenuTab.HOME);
    }

    @Test
    void seededInitialTabStillTogglesClosedOnSecondSelect() {
        // FR2.2's existing toggle-to-deselect behavior must be unaffected by
        // the new seeded-initial-tab constructor path once the tab bar is
        // interacted with.
        MainMenuStateMachine seeded = new MainMenuStateMachine(MainMenuTab.HOME);
        seeded.selectTab(MainMenuTab.HOME);
        assertThat(seeded.activeTab()).isNull();
    }

    @Test
    void nullConstructorArgumentStartsWithNoTabActive() {
        assertThat(new MainMenuStateMachine(null).activeTab()).isNull();
    }

    @Test
    void selectingATabOpensIt() {
        state.selectTab(MainMenuTab.WORLDS);
        assertThat(state.activeTab()).isEqualTo(MainMenuTab.WORLDS);
    }

    @Test
    void selectingTheActiveTabAgainDeselectsIt() {
        state.selectTab(MainMenuTab.WORLDS);
        state.selectTab(MainMenuTab.WORLDS);
        assertThat(state.activeTab()).isNull();
    }

    @Test
    void selectingADifferentTabSwitchesDirectly() {
        state.selectTab(MainMenuTab.WORLDS);
        state.selectTab(MainMenuTab.SERVERS);
        assertThat(state.activeTab()).isEqualTo(MainMenuTab.SERVERS);
    }

    @Test
    void freshInstanceHasNoExpandedRow() {
        assertThat(state.expandedRowId()).isNull();
    }

    @Test
    void expandingARowSelectsIt() {
        state.toggleRowExpanded("world-1");
        assertThat(state.expandedRowId()).isEqualTo("world-1");
    }

    @Test
    void expandingTheSameRowAgainCollapsesIt() {
        state.toggleRowExpanded("world-1");
        state.toggleRowExpanded("world-1");
        assertThat(state.expandedRowId()).isNull();
    }

    @Test
    void expandingADifferentRowCollapsesThePreviousOne() {
        state.toggleRowExpanded("world-1");
        state.toggleRowExpanded("world-2");
        assertThat(state.expandedRowId()).isEqualTo("world-2");
    }

    @Test
    void freshInstanceDefaultsToSavedSubView() {
        assertThat(state.serversSubView()).isEqualTo(MainMenuStateMachine.ServersSubView.SAVED);
    }

    @Test
    void serversSubViewToggles() {
        state.setServersSubView(MainMenuStateMachine.ServersSubView.BROWSER);
        assertThat(state.serversSubView()).isEqualTo(MainMenuStateMachine.ServersSubView.BROWSER);
        state.setServersSubView(MainMenuStateMachine.ServersSubView.SAVED);
        assertThat(state.serversSubView()).isEqualTo(MainMenuStateMachine.ServersSubView.SAVED);
    }

    @Test
    void freshInstanceDefaultsToHeadWardrobeSlot() {
        assertThat(state.activeWardrobeSlot()).isEqualTo(WardrobeSlot.HEAD);
    }

    @Test
    void selectingAWardrobeSlotSwitchesTheActiveSlot() {
        state.selectWardrobeSlot(WardrobeSlot.FEET);
        assertThat(state.activeWardrobeSlot()).isEqualTo(WardrobeSlot.FEET);
    }

    @Test
    void equippingAnItemSetsIt() {
        state.equip(WardrobeSlot.TORSO, "moss-cloak");
        assertThat(state.equippedItemId(WardrobeSlot.TORSO)).isEqualTo("moss-cloak");
    }

    @Test
    void equippingAnItemReplacesThePriorEquipForThatSlot() {
        state.equip(WardrobeSlot.TORSO, "moss-cloak");
        state.equip(WardrobeSlot.TORSO, "other-cloak");
        assertThat(state.equippedItemId(WardrobeSlot.TORSO)).isEqualTo("other-cloak");
    }

    @Test
    void equipSnapshotReflectsEveryEquippedSlot() {
        state.equip(WardrobeSlot.TORSO, "moss-cloak");
        state.equip(WardrobeSlot.HEAD, "wanderer-hood");
        assertThat(state.equipSnapshot()).containsExactlyInAnyOrderEntriesOf(
                Map.of(WardrobeSlot.TORSO, "moss-cloak", WardrobeSlot.HEAD, "wanderer-hood"));
    }

    @Test
    void loadEquippedSeedsFromPersistedState() {
        state.loadEquipped(Map.of(WardrobeSlot.FEET, "sturdy-boots"));
        assertThat(state.equippedItemId(WardrobeSlot.FEET)).isEqualTo("sturdy-boots");
    }

    @Test
    void modalFlagsDefaultToClosed() {
        assertThat(state.isDirectConnectModalOpen()).isFalse();
        assertThat(state.isAddServerModalOpen()).isFalse();
        assertThat(state.isWorldCreateToastVisible()).isFalse();
    }

    @Test
    void directConnectModalOpensAndCloses() {
        state.openDirectConnectModal();
        assertThat(state.isDirectConnectModalOpen()).isTrue();
        state.closeDirectConnectModal();
        assertThat(state.isDirectConnectModalOpen()).isFalse();
    }

    @Test
    void addServerModalOpensAndCloses() {
        state.openAddServerModal();
        assertThat(state.isAddServerModalOpen()).isTrue();
        state.closeAddServerModal();
        assertThat(state.isAddServerModalOpen()).isFalse();
    }

    @Test
    void worldCreateToastShowsAndHides() {
        state.showWorldCreateToast();
        assertThat(state.isWorldCreateToastVisible()).isTrue();
        state.hideWorldCreateToast();
        assertThat(state.isWorldCreateToastVisible()).isFalse();
    }
}
