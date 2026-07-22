package de.lazuli.mainmenu;

import de.lazuli.api.mainmenu.StoreItem;
import de.lazuli.features.mainmenu.services.StoreCatalog;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Store tab panel (specification FR5, plan Decision 1): a featured-item
 * banner plus an "All Cosmetics" 3-column grid, backed by {@link StoreCatalog}
 * (featured-item selection/category grouping, Step 3) and the injected
 * {@link MainMenuStoreOwnershipChecker} (real path)/DLC-fallback ownership.
 *
 * <p>"Buy Now"/"Buy" wiring (FR5.3): {@code inventoryItemDefId}-configured
 * items call the fork's {@code SteamInventory.startPurchase}; {@code
 * steamDlcAppId}-only items call {@code SteamFriends.activateGameOverlayToStore}.
 * An item with neither identifier configured renders a disabled "Not
 * available yet" placeholder instead of a Buy pill (the honest, expected
 * dev-time default, spec FR5.2).
 */
public final class StorePanel {

    private static final int CARD_SIZE = 96;
    private static final int CARD_GAP = 12;

    private final StoreCatalog catalog;
    private final MainMenuStoreOwnershipChecker ownershipChecker;

    public StorePanel(StoreCatalog catalog, MainMenuStoreOwnershipChecker ownershipChecker) {
        this.catalog = catalog;
        this.ownershipChecker = ownershipChecker;
    }

    public void render(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int contentY = y;

        Optional<StoreItem> featured = catalog.featuredItem();
        if (featured.isPresent()) {
            StoreItem item = featured.get();
            int bannerHeight = 72;
            guiGraphics.fill(x, contentY, x + width, contentY + bannerHeight, 0xFF251F17);
            guiGraphics.fill(x, contentY, x + 6, contentY + bannerHeight, 0xFF528A54);
            guiGraphics.text(font, Component.literal("FEATURED"), x + 16, contentY + 6, 0xFF95C97F);
            guiGraphics.text(font, Component.literal(item.displayName()), x + 16, contentY + 20, 0xFFEAE8E1);
            guiGraphics.text(font, Component.literal(item.description()), x + 16, contentY + 32, 0xFF908C7F);
            renderPrice(guiGraphics, font, item, x + 16, contentY + 48);
            renderBuyPill(guiGraphics, font, item, x + width - 120, contentY + 44, 104, 20);
            contentY += bannerHeight + 16;
        }

        guiGraphics.text(font, Component.literal("All Cosmetics"), x, contentY, 0xFFEAE8E1);
        contentY += 16;

        int columns = Math.max(1, (width + CARD_GAP) / (CARD_SIZE + CARD_GAP));
        int col = 0;
        int rowY = contentY;
        for (Map.Entry<String, List<StoreItem>> entry : catalog.itemsByCategory().entrySet()) {
            for (StoreItem item : entry.getValue()) {
                int cardX = x + col * (CARD_SIZE + CARD_GAP);
                boolean hovered = mouseX >= cardX && mouseX <= cardX + CARD_SIZE && mouseY >= rowY && mouseY <= rowY + CARD_SIZE;
                int bg = hovered ? 0xFF2E3A26 : 0xFF201E17;
                int cardTop = hovered ? rowY - 3 : rowY;
                guiGraphics.fill(cardX, cardTop, cardX + CARD_SIZE, cardTop + CARD_SIZE, bg);
                if (hovered) {
                    guiGraphics.fill(cardX, cardTop, cardX + CARD_SIZE, cardTop + 2, 0xFF528A54);
                }
                // Placeholder diagonal-stripe swatch (spec Non-goals: no real icon textures yet).
                guiGraphics.fill(cardX + 8, cardTop + 8, cardX + CARD_SIZE - 8, cardTop + 48, 0xFF3A362B);
                guiGraphics.text(font, Component.literal(item.displayName()), cardX + 8, cardTop + 52, 0xFFEAE8E1);
                guiGraphics.text(font, Component.literal(item.category()), cardX + 8, cardTop + 63, 0xFF908C7F);
                renderPrice(guiGraphics, font, item, cardX + 8, cardTop + 74);
                renderBuyPill(guiGraphics, font, item, cardX + 8, cardTop + CARD_SIZE - 18, CARD_SIZE - 16, 16);

                col++;
                if (col >= columns) {
                    col = 0;
                    rowY += CARD_SIZE + CARD_GAP;
                }
            }
        }
    }

    private void renderPrice(GuiGraphicsExtractor guiGraphics, Font font, StoreItem item, int x, int y) {
        String price = "$" + String.format("%.2f", item.priceCents() / 100.0);
        guiGraphics.text(font, Component.literal(price), x, y, 0xFFEAE8E1);
        if (item.originalPriceCents().isPresent()) {
            String original = "$" + String.format("%.2f", item.originalPriceCents().getAsInt() / 100.0);
            guiGraphics.text(font, Component.literal("§m" + original), x + font.width(price) + 6, y, 0xFF908C7F);
        }
    }

    private void renderBuyPill(GuiGraphicsExtractor guiGraphics, Font font, StoreItem item, int x, int y, int w, int h) {
        boolean owned = catalog.isOwned(item);
        boolean purchasable = item.inventoryItemDefId().isPresent() || item.steamDlcAppId().isPresent();
        String label = owned ? "Owned" : (purchasable ? "Buy Now" : "Not available yet");
        int color = owned ? 0xFF3A6B3C : (purchasable ? 0xFF528A54 : 0xFF3A382E);
        guiGraphics.fill(x, y, x + w, y + h, color);
        guiGraphics.centeredText(font, Component.literal(label), x + w / 2, y + h / 2 - 4, 0xFFFFFFFF);
    }

    /** @return true if this click was consumed by a Buy pill in this panel. */
    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        int contentY = y;
        Optional<StoreItem> featured = catalog.featuredItem();
        if (featured.isPresent()) {
            int bannerHeight = 72;
            if (mouseX >= x + width - 120 && mouseX <= x + width - 16 && mouseY >= contentY + 44 && mouseY <= contentY + 64) {
                onBuyClicked(featured.get());
                return true;
            }
            contentY += bannerHeight + 16;
        }
        contentY += 16;

        int columns = Math.max(1, (width + CARD_GAP) / (CARD_SIZE + CARD_GAP));
        int col = 0;
        int rowY = contentY;
        for (Map.Entry<String, List<StoreItem>> entry : catalog.itemsByCategory().entrySet()) {
            for (StoreItem item : entry.getValue()) {
                int cardX = x + col * (CARD_SIZE + CARD_GAP);
                int pillY = rowY + CARD_SIZE - 18;
                if (mouseX >= cardX + 8 && mouseX <= cardX + CARD_SIZE - 8 && mouseY >= pillY && mouseY <= pillY + 16) {
                    onBuyClicked(item);
                    return true;
                }
                col++;
                if (col >= columns) {
                    col = 0;
                    rowY += CARD_SIZE + CARD_GAP;
                }
            }
        }
        return false;
    }

    private void onBuyClicked(StoreItem item) {
        if (catalog.isOwned(item)) {
            return;
        }
        ownershipChecker.buy(item);
    }
}
