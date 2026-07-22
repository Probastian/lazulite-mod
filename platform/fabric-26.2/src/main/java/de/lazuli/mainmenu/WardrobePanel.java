package de.lazuli.mainmenu;

import de.lazuli.api.mainmenu.StoreItem;
import de.lazuli.api.mainmenu.WardrobeSlot;
import de.lazuli.features.mainmenu.services.MainMenuStateMachine;
import de.lazuli.features.mainmenu.services.StoreCatalog;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Wardrobe tab panel (specification FR6): a Head/Torso/Legs/Feet slot
 * selector row plus a 3-column item grid (owned-or-equipped items for the
 * active slot only), backed by the same {@link StoreCatalog}/ownership
 * seam the Store panel uses (Step 10). Clicking an item equips it into the
 * active slot, persisted write-through by {@code onEquip} (spec FR6.3,
 * wired from {@code MainMenuClientInitializer}).
 *
 * <p>Explicit non-goal (spec FR6.4): no skin-layer rendering of equipped
 * cosmetics onto the actual player model -- this panel only tracks/displays
 * the equip choice.
 */
public final class WardrobePanel {

    private static final int CARD_SIZE = 96;
    private static final int CARD_GAP = 12;
    private static final WardrobeSlot[] SLOTS = WardrobeSlot.values();

    private final MainMenuStateMachine state;
    private final StoreCatalog catalog;
    private final BiConsumer<WardrobeSlot, String> onEquip;

    public WardrobePanel(MainMenuStateMachine state, StoreCatalog catalog, BiConsumer<WardrobeSlot, String> onEquip) {
        this.state = state;
        this.catalog = catalog;
        this.onEquip = onEquip;
    }

    public void render(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int slotButtonWidth = width / SLOTS.length;
        for (int i = 0; i < SLOTS.length; i++) {
            WardrobeSlot slot = SLOTS[i];
            int slotX = x + i * slotButtonWidth;
            boolean active = slot == state.activeWardrobeSlot();
            boolean hovered = mouseX >= slotX && mouseX <= slotX + slotButtonWidth && mouseY >= y && mouseY <= y + 26;
            guiGraphics.fill(slotX, y, slotX + slotButtonWidth - 2, y + 26, active ? 0xFF3A6B3C : (hovered ? 0xFF2A2820 : 0xFF201E17));
            guiGraphics.centeredText(font, Component.literal(slotLabel(slot)), slotX + slotButtonWidth / 2, y + 9, active ? 0xFFFFFFFF : 0xFFEAE8E1);
        }

        int gridY = y + 40;
        List<StoreItem> eligible = eligibleItems(state.activeWardrobeSlot());
        if (eligible.isEmpty()) {
            guiGraphics.text(font, Component.literal("No owned items for this slot yet."), x, gridY, 0xFF908C7F);
            return;
        }

        int columns = Math.max(1, (width + CARD_GAP) / (CARD_SIZE + CARD_GAP));
        int col = 0;
        int rowY = gridY;
        for (StoreItem item : eligible) {
            int cardX = x + col * (CARD_SIZE + CARD_GAP);
            boolean equipped = item.id().equals(state.equippedItemId(state.activeWardrobeSlot()));
            boolean hovered = mouseX >= cardX && mouseX <= cardX + CARD_SIZE && mouseY >= rowY && mouseY <= rowY + CARD_SIZE;
            int bg = equipped ? 0xFF2E3A26 : (hovered ? 0xFF2A2820 : 0xFF201E17);
            guiGraphics.fill(cardX, rowY, cardX + CARD_SIZE, rowY + CARD_SIZE, bg);
            if (equipped) {
                guiGraphics.fill(cardX, rowY, cardX + CARD_SIZE, rowY + 2, 0xFF528A54);
            }
            guiGraphics.fill(cardX + 8, rowY + 8, cardX + CARD_SIZE - 8, rowY + 48, 0xFF3A362B);
            guiGraphics.text(font, Component.literal(item.displayName()), cardX + 8, rowY + 52, 0xFFEAE8E1);
            String status = equipped ? "Equipped" : "Owned";
            int statusColor = equipped ? 0xFF95C97F : 0xFF908C7F;
            guiGraphics.text(font, Component.literal(status), cardX + 8, rowY + 64, statusColor);

            col++;
            if (col >= columns) {
                col = 0;
                rowY += CARD_SIZE + CARD_GAP;
            }
        }
    }

    private List<StoreItem> eligibleItems(WardrobeSlot slot) {
        String equippedId = state.equippedItemId(slot);
        List<StoreItem> result = new ArrayList<>();
        List<StoreItem> categoryItems = catalog.itemsByCategory().getOrDefault(slot.name(), List.of());
        for (StoreItem item : categoryItems) {
            if (catalog.isOwned(item) || item.id().equals(equippedId)) {
                result.add(item);
            }
        }
        return result;
    }

    private static String slotLabel(WardrobeSlot slot) {
        return switch (slot) {
            case HEAD -> "Head";
            case TORSO -> "Torso";
            case LEGS -> "Legs";
            case FEET -> "Feet";
        };
    }

    /** @return true if this click was consumed by the slot selector or an item card in this panel. */
    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        int slotButtonWidth = width / SLOTS.length;
        if (mouseY >= y && mouseY <= y + 26) {
            for (int i = 0; i < SLOTS.length; i++) {
                int slotX = x + i * slotButtonWidth;
                if (mouseX >= slotX && mouseX <= slotX + slotButtonWidth - 2) {
                    state.selectWardrobeSlot(SLOTS[i]);
                    return true;
                }
            }
        }

        int gridY = y + 40;
        List<StoreItem> eligible = eligibleItems(state.activeWardrobeSlot());
        int columns = Math.max(1, (width + CARD_GAP) / (CARD_SIZE + CARD_GAP));
        int col = 0;
        int rowY = gridY;
        for (StoreItem item : eligible) {
            int cardX = x + col * (CARD_SIZE + CARD_GAP);
            if (mouseX >= cardX && mouseX <= cardX + CARD_SIZE && mouseY >= rowY && mouseY <= rowY + CARD_SIZE) {
                WardrobeSlot slot = state.activeWardrobeSlot();
                state.equip(slot, item.id());
                onEquip.accept(slot, item.id());
                return true;
            }
            col++;
            if (col >= columns) {
                col = 0;
                rowY += CARD_SIZE + CARD_GAP;
            }
        }
        return false;
    }
}
