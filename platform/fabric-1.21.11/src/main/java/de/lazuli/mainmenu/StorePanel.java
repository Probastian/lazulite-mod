package de.lazuli.mainmenu;

import de.lazuli.api.mainmenu.StoreItem;
import de.lazuli.features.mainmenu.services.StoreCatalog;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Store tab panel (specification FR5, plan Decision 1) -- {@code fabric-1.21.11}
 * (Yarn-mapped, obfuscated) port of the {@code fabric-26.1}/{@code fabric-26.2}
 * class of the same name.
 */
public final class StorePanel {

    private static final int CARD_WIDTH = 96;
    private static final int CARD_GAP = 12;
    // FR-B3.8: near-full-card square swatch (design handoff's "square swatch,
    // aspect-ratio 1:1" grid-card convention), rather than the previous
    // ~42%-card-height placeholder fill -- the card grows taller than
    // CARD_WIDTH to fit name/category/price/Buy below the swatch, but stays
    // CARD_WIDTH wide (column layout unaffected).
    private static final int SWATCH_MARGIN = 6;
    private static final int SWATCH_SIZE = CARD_WIDTH - SWATCH_MARGIN * 2;
    private static final int CARD_TEXT_ZONE = 50;
    private static final int CARD_HEIGHT = SWATCH_MARGIN + SWATCH_SIZE + 4 + CARD_TEXT_ZONE;
    private static final int CONTENT_LEFT_PAD = 8;

    private final StoreCatalog catalog;
    private final MainMenuStoreOwnershipChecker ownershipChecker;

    public StorePanel(StoreCatalog catalog, MainMenuStoreOwnershipChecker ownershipChecker) {
        this.catalog = catalog;
        this.ownershipChecker = ownershipChecker;
    }

    public void render(DrawContext context, TextRenderer font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int leftX = x + CONTENT_LEFT_PAD;
        int contentWidth = width - CONTENT_LEFT_PAD;
        int contentY = y;

        Optional<StoreItem> featured = catalog.featuredItem();
        if (featured.isPresent()) {
            StoreItem item = featured.get();
            // FR-B3.7: banner height increased to accommodate a large item
            // swatch inline (design handoff's "130x130px, diagonal-stripe
            // placeholder texture", scaled down here to fit the panel's
            // available banner width) in a swatch-left/text-right layout.
            int bannerHeight = 96;
            int swatchSize = bannerHeight - 16;
            context.fill(leftX, contentY, x + width, contentY + bannerHeight, 0xFF251F17);
            context.fill(leftX, contentY, leftX + 6, contentY + bannerHeight, 0xFF528A54);
            int swatchX = leftX + 16;
            int swatchY = contentY + 8;
            context.fill(swatchX, swatchY, swatchX + swatchSize, swatchY + swatchSize, 0xFF3A362B);
            int textX = swatchX + swatchSize + 16;
            context.drawText(font, Text.literal("FEATURED"), textX, contentY + 8, 0xFF95C97F, false);
            context.drawText(font, Text.literal(item.displayName()), textX, contentY + 22, 0xFFEAE8E1, false);
            context.drawText(font, Text.literal(item.description()), textX, contentY + 34, 0xFF908C7F, false);
            renderPrice(context, font, item, textX, contentY + 50);
            renderBuyPill(context, font, item, x + width - 120, contentY + bannerHeight - 24, 104, 20);
            contentY += bannerHeight + 16;
        }

        context.drawText(font, Text.literal("All Cosmetics"), leftX, contentY, 0xFFEAE8E1, false);
        contentY += 16;

        int columns = Math.max(1, (contentWidth + CARD_GAP) / (CARD_WIDTH + CARD_GAP));
        int col = 0;
        int rowY = contentY;
        for (Map.Entry<String, List<StoreItem>> entry : catalog.itemsByCategory().entrySet()) {
            for (StoreItem item : entry.getValue()) {
                int cardX = leftX + col * (CARD_WIDTH + CARD_GAP);
                boolean hovered = mouseX >= cardX && mouseX <= cardX + CARD_WIDTH && mouseY >= rowY && mouseY <= rowY + CARD_HEIGHT;
                int bg = hovered ? 0xFF2E3A26 : 0xFF201E17;
                int cardTop = hovered ? rowY - 3 : rowY;
                context.fill(cardX, cardTop, cardX + CARD_WIDTH, cardTop + CARD_HEIGHT, bg);
                if (hovered) {
                    context.fill(cardX, cardTop, cardX + CARD_WIDTH, cardTop + 2, 0xFF528A54);
                }
                // FR-B3.8: near-full-card square swatch (placeholder fill,
                // spec Non-goals: no real icon textures yet).
                int swatchTop = cardTop + SWATCH_MARGIN;
                context.fill(cardX + SWATCH_MARGIN, swatchTop, cardX + SWATCH_MARGIN + SWATCH_SIZE,
                        swatchTop + SWATCH_SIZE, 0xFF3A362B);
                int textY = swatchTop + SWATCH_SIZE + 4;
                context.drawText(font, Text.literal(item.displayName()), cardX + 8, textY, 0xFFEAE8E1, false);
                context.drawText(font, Text.literal(item.category()), cardX + 8, textY + 11, 0xFF908C7F, false);
                renderPrice(context, font, item, cardX + 8, textY + 22);
                renderBuyPill(context, font, item, cardX + 8, textY + 34, CARD_WIDTH - 16, 16);

                col++;
                if (col >= columns) {
                    col = 0;
                    rowY += CARD_HEIGHT + CARD_GAP;
                }
            }
        }
    }

    private void renderPrice(DrawContext context, TextRenderer font, StoreItem item, int x, int y) {
        String price = "$" + String.format("%.2f", item.priceCents() / 100.0);
        context.drawText(font, Text.literal(price), x, y, 0xFFEAE8E1, false);
        if (item.originalPriceCents().isPresent()) {
            String original = "$" + String.format("%.2f", item.originalPriceCents().getAsInt() / 100.0);
            context.drawText(font, Text.literal("§m" + original), x + font.getWidth(price) + 6, y, 0xFF908C7F, false);
        }
    }

    private void renderBuyPill(DrawContext context, TextRenderer font, StoreItem item, int x, int y, int w, int h) {
        boolean owned = catalog.isOwned(item);
        boolean purchasable = item.inventoryItemDefId().isPresent() || item.steamDlcAppId().isPresent();
        String label = owned ? "Owned" : (purchasable ? "Buy Now" : "Not available yet");
        int color = owned ? 0xFF3A6B3C : (purchasable ? 0xFF528A54 : 0xFF3A382E);
        context.fill(x, y, x + w, y + h, color);
        context.drawCenteredTextWithShadow(font, label, x + w / 2, y + h / 2 - 4, 0xFFFFFFFF);
    }

    /** @return true if this click was consumed by a Buy pill in this panel. */
    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        int leftX = x + CONTENT_LEFT_PAD;
        int contentWidth = width - CONTENT_LEFT_PAD;
        int contentY = y;
        Optional<StoreItem> featured = catalog.featuredItem();
        if (featured.isPresent()) {
            int bannerHeight = 96;
            int pillTop = contentY + bannerHeight - 24;
            if (mouseX >= x + width - 120 && mouseX <= x + width - 16 && mouseY >= pillTop && mouseY <= pillTop + 20) {
                onBuyClicked(featured.get());
                return true;
            }
            contentY += bannerHeight + 16;
        }
        contentY += 16;

        int columns = Math.max(1, (contentWidth + CARD_GAP) / (CARD_WIDTH + CARD_GAP));
        int col = 0;
        int rowY = contentY;
        for (Map.Entry<String, List<StoreItem>> entry : catalog.itemsByCategory().entrySet()) {
            for (StoreItem item : entry.getValue()) {
                int cardX = leftX + col * (CARD_WIDTH + CARD_GAP);
                int textY = rowY + SWATCH_MARGIN + SWATCH_SIZE + 4;
                int pillY = textY + 34;
                if (mouseX >= cardX + 8 && mouseX <= cardX + CARD_WIDTH - 8 && mouseY >= pillY && mouseY <= pillY + 16) {
                    onBuyClicked(item);
                    return true;
                }
                col++;
                if (col >= columns) {
                    col = 0;
                    rowY += CARD_HEIGHT + CARD_GAP;
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
