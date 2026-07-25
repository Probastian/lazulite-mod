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

    private static final int CARD_WIDTH = 96;
    private static final int CARD_GAP = 12;
    // FR-B3.10: same near-full-card square swatch convention as StorePanel's
    // grid (FR-B3.8) -- shared sizing constants, kept in lockstep deliberately.
    private static final int SWATCH_MARGIN = 6;
    private static final int SWATCH_SIZE = CARD_WIDTH - SWATCH_MARGIN * 2;
    private static final int CARD_TEXT_ZONE = 26;
    private static final int CARD_HEIGHT = SWATCH_MARGIN + SWATCH_SIZE + 4 + CARD_TEXT_ZONE;

    private static final int SLOT_BUTTON_HEIGHT = 42;
    private static final int SLOT_SWATCH_SIZE = 16;
    private static final int CONTENT_LEFT_PAD = 8;

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
            boolean hovered = mouseX >= slotX && mouseX <= slotX + slotButtonWidth && mouseY >= y && mouseY <= y + SLOT_BUTTON_HEIGHT;
            guiGraphics.fill(slotX, y, slotX + slotButtonWidth - 2, y + SLOT_BUTTON_HEIGHT,
                    active ? 0xFF3A6B3C : (hovered ? 0xFF2A2820 : 0xFF201E17));

            // FR-B3.9: each slot button shows a small swatch of the currently
            // equipped item + the slot label + the equipped item's
            // (truncated) name -- previously only the slot label was drawn.
            int swatchX = slotX + 6;
            int swatchY = y + 6;
            guiGraphics.fill(swatchX, swatchY, swatchX + SLOT_SWATCH_SIZE, swatchY + SLOT_SWATCH_SIZE, 0xFF3A362B);
            int labelX = swatchX + SLOT_SWATCH_SIZE + 6;
            guiGraphics.text(font, Component.literal(slotLabel(slot)), labelX, y + 6, active ? 0xFFFFFFFF : 0xFFEAE8E1);
            String equippedName = truncate(font, equippedItemName(slot), slotButtonWidth - 12);
            guiGraphics.text(font, Component.literal(equippedName), slotX + 6, y + SLOT_SWATCH_SIZE + 12,
                    active ? 0xFFDCE8DC : 0xFF908C7F);
        }

        int leftX = x + CONTENT_LEFT_PAD;
        int contentWidth = width - CONTENT_LEFT_PAD;
        int gridY = y + SLOT_BUTTON_HEIGHT + 8;
        List<StoreItem> eligible = eligibleItems(state.activeWardrobeSlot());
        if (eligible.isEmpty()) {
            guiGraphics.text(font, Component.literal("No owned items for this slot yet."), leftX, gridY, 0xFF908C7F);
            return;
        }

        int columns = Math.max(1, (contentWidth + CARD_GAP) / (CARD_WIDTH + CARD_GAP));
        int col = 0;
        int rowY = gridY;
        for (StoreItem item : eligible) {
            int cardX = leftX + col * (CARD_WIDTH + CARD_GAP);
            boolean equipped = item.id().equals(state.equippedItemId(state.activeWardrobeSlot()));
            boolean hovered = mouseX >= cardX && mouseX <= cardX + CARD_WIDTH && mouseY >= rowY && mouseY <= rowY + CARD_HEIGHT;
            int bg = equipped ? 0xFF2E3A26 : (hovered ? 0xFF2A2820 : 0xFF201E17);
            guiGraphics.fill(cardX, rowY, cardX + CARD_WIDTH, rowY + CARD_HEIGHT, bg);
            if (equipped) {
                guiGraphics.fill(cardX, rowY, cardX + CARD_WIDTH, rowY + 2, 0xFF528A54);
            }
            // FR-B3.10: near-full-card square swatch (placeholder fill,
            // same convention as StorePanel's grid, FR-B3.8).
            int swatchTop = rowY + SWATCH_MARGIN;
            guiGraphics.fill(cardX + SWATCH_MARGIN, swatchTop, cardX + SWATCH_MARGIN + SWATCH_SIZE,
                    swatchTop + SWATCH_SIZE, 0xFF3A362B);
            int textY = swatchTop + SWATCH_SIZE + 4;
            guiGraphics.text(font, Component.literal(item.displayName()), cardX + 8, textY, 0xFFEAE8E1);
            String status = equipped ? "Equipped" : "Owned";
            int statusColor = equipped ? 0xFF95C97F : 0xFF908C7F;
            guiGraphics.text(font, Component.literal(status), cardX + 8, textY + 11, statusColor);

            col++;
            if (col >= columns) {
                col = 0;
                rowY += CARD_HEIGHT + CARD_GAP;
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

    /** @return the display name of the item currently equipped in {@code slot}, or "None" if nothing is equipped. */
    private String equippedItemName(WardrobeSlot slot) {
        String equippedId = state.equippedItemId(slot);
        if (equippedId == null) {
            return "None";
        }
        for (StoreItem item : catalog.itemsByCategory().getOrDefault(slot.name(), List.of())) {
            if (item.id().equals(equippedId)) {
                return item.displayName();
            }
        }
        return equippedId;
    }

    /** Plain truncation with a trailing ellipsis once {@code text} exceeds {@code maxWidth}. */
    private static String truncate(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String candidate = sb.toString() + text.charAt(i) + ellipsis;
            if (font.width(candidate) > maxWidth) {
                break;
            }
            sb.append(text.charAt(i));
        }
        return sb + ellipsis;
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
        if (mouseY >= y && mouseY <= y + SLOT_BUTTON_HEIGHT) {
            for (int i = 0; i < SLOTS.length; i++) {
                int slotX = x + i * slotButtonWidth;
                if (mouseX >= slotX && mouseX <= slotX + slotButtonWidth - 2) {
                    state.selectWardrobeSlot(SLOTS[i]);
                    return true;
                }
            }
        }

        int leftX = x + CONTENT_LEFT_PAD;
        int contentWidth = width - CONTENT_LEFT_PAD;
        int gridY = y + SLOT_BUTTON_HEIGHT + 8;
        List<StoreItem> eligible = eligibleItems(state.activeWardrobeSlot());
        int columns = Math.max(1, (contentWidth + CARD_GAP) / (CARD_WIDTH + CARD_GAP));
        int col = 0;
        int rowY = gridY;
        for (StoreItem item : eligible) {
            int cardX = leftX + col * (CARD_WIDTH + CARD_GAP);
            if (mouseX >= cardX && mouseX <= cardX + CARD_WIDTH && mouseY >= rowY && mouseY <= rowY + CARD_HEIGHT) {
                WardrobeSlot slot = state.activeWardrobeSlot();
                state.equip(slot, item.id());
                onEquip.accept(slot, item.id());
                return true;
            }
            col++;
            if (col >= columns) {
                col = 0;
                rowY += CARD_HEIGHT + CARD_GAP;
            }
        }
        return false;
    }
}
