package de.lazuli.tweaks;

import de.lazuli.api.tweaks.TweakId;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loot Bin UI (docs/specs/tweaks-loot-bin-ui.md) replacement container
 * screen -- 26.2 Mojmap copy (byte-identical to the 26.1 copy of this file
 * apart from imports, per this repo's own per-platform-file duplication
 * convention, {@code FreecamCameraEntity}/{@code FreecamTicker} precedent).
 *
 * <p><strong>Rendering (R8-R10, R16, Architecture "Screen-replacement
 * mechanism"):</strong> when {@link #groupedViewActive}, this screen draws
 * its own minimal, dark, vanilla-width panel (R8) in place of vanilla's own
 * stretched chest texture, a scrollable grouped/searchable item list, and a
 * divider strip (search bar + fullness indicator, R16) -- then renders the
 * player-inventory region by calling the inherited, unmodified {@link
 * #extractSlot} once per real player-owned {@link Slot} (R9: same per-slot
 * draw call vanilla's own default rendering already uses, just selectively
 * invoked instead of decomposing the whole monolithic slot loop). When
 * {@code !groupedViewActive} (R15's vanilla-grid fallback), this screen
 * delegates the *entire* frame to the unmodified superclass render, with
 * only a small mode-indicator label painted on top (a cosmetic overlay, not
 * a rendering/interaction override -- R15's "zero override active" promise
 * is about slot drawing/click routing, not this one label).
 *
 * <p><strong>Click routing (R11-R13, the anti-cheat-safety core
 * requirement):</strong> every aggregated-row gesture resolves to exactly
 * one real backing {@link Slot} (R12) and issues exactly one inherited,
 * protected {@link #slotClicked} call (R13) -- the exact same choke point
 * vanilla's own default click routing already uses. Clicks landing in the
 * player-inventory region (below the divider strip) are delegated untouched
 * to {@code super.mouseClicked(...)}, which hit-tests against those real
 * slots' own unmoved {@code x}/{@code y} exactly as vanilla always has.
 *
 * <p><strong>Panel sizing:</strong> width is fixed at vanilla's own
 * inventory/container UI width (176px, R8); height is derived from the
 * container's own real (server-baked, immutable) player-inventory slot
 * positions ({@link LootBinGrouping#playerInvBounds}) so the player-
 * inventory region keeps rendering at its real, unmoved coordinates (R9)
 * while the grouped-list area above it uses whatever vertical budget that
 * leaves, bounded per R8's "modestly taller, never much taller than a
 * double chest" intent by the container's own row count.
 */
public final class LootBinScreen<T extends AbstractContainerMenu> extends AbstractContainerScreen<T> {

    private static final int PANEL_WIDTH = 176;
    private static final int TOP_MARGIN = 18;
    private static final int GAP_ABOVE_DIVIDER = 4;
    private static final int DIVIDER_HEIGHT = 22;
    private static final int BOTTOM_MARGIN = 6;
    private static final int ROW_HEIGHT = 20;
    private static final int HEADER_HEIGHT = 12;
    private static final int ICON_SIZE = 16;
    private static final int SCROLL_STEP = 16;
    private static final int SEARCH_FIELD_WIDTH = 110;
    private static final int SEARCH_FIELD_HEIGHT = 12;

    private static final int COLOR_PANEL_BG = 0xCC1A1A1A;
    private static final int COLOR_DIVIDER_BG = 0xCC101010;
    private static final int COLOR_BORDER = 0xFF3A3A3A;
    private static final int COLOR_HEADER = 0xFFC9A227;
    private static final int COLOR_TEXT = 0xFFE8E6DF;
    private static final int COLOR_COUNT = 0xFFAFAFAF;
    private static final int COLOR_ROW_HOVER = 0x40FFFFFF;
    private static final int COLOR_MODE_LABEL = 0xFF9C9C9C;
    private static final int COLOR_SEARCH_BG = 0xFF262523;
    private static final int COLOR_SEARCH_FOCUSED = 0xFF3A3020;

    private final Inventory playerInventory;

    /** R15: transient, never persisted, resets to grouped-mode-on every fresh open. */
    private boolean groupedViewActive = true;
    private boolean searchFocused;
    private String searchText = "";
    private int scrollOffset;

    private int listTop;
    private int listHeight;
    private int dividerTop;
    private int searchFieldX;
    private int searchFieldY;
    private int lastContentBottom;

    public LootBinScreen(T menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, PANEL_WIDTH, computeHeight(menu, playerInventory));
        this.playerInventory = playerInventory;
    }

    private static int computeHeight(AbstractContainerMenu menu, Inventory playerInventory) {
        LootBinGrouping.PlayerInvBounds bounds = LootBinGrouping.playerInvBounds(menu.slots, playerInventory);
        int listAreaHeight = Math.max(60, bounds.top() - TOP_MARGIN - GAP_ABOVE_DIVIDER - DIVIDER_HEIGHT);
        return TOP_MARGIN + listAreaHeight + GAP_ABOVE_DIVIDER + DIVIDER_HEIGHT
                + (bounds.bottom() - bounds.top()) + BOTTOM_MARGIN;
    }

    @Override
    protected void init() {
        super.init();
        LootBinGrouping.PlayerInvBounds bounds = LootBinGrouping.playerInvBounds(this.menu.slots, this.playerInventory);
        listTop = this.topPos + TOP_MARGIN;
        dividerTop = this.topPos + bounds.top() - GAP_ABOVE_DIVIDER - DIVIDER_HEIGHT;
        listHeight = Math.max(20, dividerTop - listTop);
        searchFieldX = this.leftPos + 6;
        searchFieldY = dividerTop + (DIVIDER_HEIGHT - SEARCH_FIELD_HEIGHT) / 2;
    }

    // ---- Rendering -------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!groupedViewActive) {
            super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
            renderModeLabel(guiGraphics);
            return;
        }

        guiGraphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + this.imageHeight, COLOR_PANEL_BG);
        drawBorder(guiGraphics, this.leftPos, this.topPos, this.imageWidth, this.imageHeight);
        guiGraphics.text(this.font, this.title, this.leftPos + 6, this.topPos + 6, COLOR_HEADER);
        renderModeLabel(guiGraphics);

        renderGroupedList(guiGraphics, mouseX, mouseY);
        renderDivider(guiGraphics, mouseX, mouseY);

        // R9: player-inventory region -- reuse the exact vanilla per-slot draw call,
        // unmodified, only for real slots backed by the player's own inventory.
        for (Slot slot : this.menu.slots) {
            if (LootBinGrouping.isPlayerSlot(slot, this.playerInventory)) {
                this.extractSlot(guiGraphics, slot, mouseX, mouseY);
                maybeShowSlotTooltip(guiGraphics, slot, mouseX, mouseY);
            }
        }
        this.extractCarriedItem(guiGraphics, mouseX, mouseY);
    }

    private void maybeShowSlotTooltip(GuiGraphicsExtractor guiGraphics, Slot slot, int mouseX, int mouseY) {
        int sx = this.leftPos + slot.x;
        int sy = this.topPos + slot.y;
        if (mouseX >= sx && mouseX < sx + ICON_SIZE && mouseY >= sy && mouseY < sy + ICON_SIZE && !slot.getItem().isEmpty()) {
            guiGraphics.setComponentTooltipForNextFrame(this.font, getTooltipFromContainerItem(slot.getItem()), mouseX, mouseY);
        }
    }

    private void renderModeLabel(GuiGraphicsExtractor guiGraphics) {
        String label = groupedViewActive ? "Grouped" : "Vanilla Grid";
        int width = this.font.width(label);
        guiGraphics.text(this.font, label, this.leftPos + this.imageWidth - width - 6, this.topPos + 6, COLOR_MODE_LABEL);
    }

    private static void drawBorder(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int height) {
        guiGraphics.fill(x, y, x + width, y + 1, COLOR_BORDER);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, COLOR_BORDER);
        guiGraphics.fill(x, y, x + 1, y + height, COLOR_BORDER);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, COLOR_BORDER);
    }

    private void renderGroupedList(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        guiGraphics.enableScissor(this.leftPos, listTop, this.leftPos + this.imageWidth, listTop + listHeight);

        List<LootBinGrouping.GroupResult> groups = LootBinGrouping.computeGroups(
                this.menu.slots, this.playerInventory, groupOrder(), sortWithinGroup());

        int rowY = listTop - scrollOffset;
        int leftEdge = this.leftPos + 6;
        int rightEdge = this.leftPos + this.imageWidth - 6;

        for (LootBinGrouping.GroupResult group : groups) {
            List<LootBinGrouping.AggregatedEntry> visible = filterBySearch(group.entries());
            if (visible.isEmpty()) {
                continue;
            }
            if (rowY + HEADER_HEIGHT > listTop && rowY < listTop + listHeight) {
                guiGraphics.text(this.font, group.tab().getDisplayName(), leftEdge, rowY + 2, COLOR_HEADER);
            }
            rowY += HEADER_HEIGHT;
            for (LootBinGrouping.AggregatedEntry entry : visible) {
                if (rowY + ROW_HEIGHT > listTop && rowY < listTop + listHeight) {
                    renderEntryRow(guiGraphics, entry, leftEdge, rightEdge, rowY, mouseX, mouseY);
                }
                rowY += ROW_HEIGHT;
            }
        }
        lastContentBottom = rowY + scrollOffset;
        guiGraphics.disableScissor();
    }

    private void renderEntryRow(GuiGraphicsExtractor guiGraphics, LootBinGrouping.AggregatedEntry entry,
            int leftEdge, int rightEdge, int rowY, int mouseX, int mouseY) {
        boolean hovered = mouseX >= leftEdge - 4 && mouseX <= rightEdge + 4 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                && mouseY >= listTop && mouseY < listTop + listHeight;
        if (hovered) {
            guiGraphics.fill(leftEdge - 4, rowY, rightEdge + 4, rowY + ROW_HEIGHT, COLOR_ROW_HOVER);
        }
        ItemStack representative = entry.key().representative();
        ItemStack icon = representative.copyWithCount(1);
        guiGraphics.item(icon, leftEdge, rowY + 1);

        String name = LootBinGrouping.displayName(representative);
        guiGraphics.text(this.font, name, leftEdge + ICON_SIZE + 4, rowY + 5, COLOR_TEXT);

        String countText = formatCount(entry);
        int countWidth = this.font.width(countText);
        guiGraphics.text(this.font, countText, rightEdge - countWidth, rowY + 5, COLOR_COUNT);
    }

    private String formatCount(LootBinGrouping.AggregatedEntry entry) {
        if ("WITH_STACK_HINT".equals(hooks().lootBinConfigurable("countTextStyle"))) {
            return entry.totalCount() + " (" + entry.stackCount() + " stack" + (entry.stackCount() == 1 ? "" : "s") + ")";
        }
        return String.valueOf(entry.totalCount());
    }

    private void renderDivider(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        int dividerBottom = dividerTop + DIVIDER_HEIGHT;
        guiGraphics.fill(this.leftPos, dividerTop, this.leftPos + this.imageWidth, dividerBottom, COLOR_DIVIDER_BG);

        int[] fullness = LootBinGrouping.containerFullness(this.menu.slots, this.playerInventory);
        String fullnessText = LootBinGrouping.fullnessText(fullness[0], fullness[1]);
        int fw = this.font.width(fullnessText);
        guiGraphics.text(this.font, fullnessText, this.leftPos + this.imageWidth - 6 - fw,
                dividerTop + (DIVIDER_HEIGHT - 8) / 2, COLOR_COUNT);

        if (showSearchBar()) {
            boolean hovered = mouseX >= searchFieldX && mouseX <= searchFieldX + SEARCH_FIELD_WIDTH
                    && mouseY >= searchFieldY && mouseY <= searchFieldY + SEARCH_FIELD_HEIGHT;
            int bg = searchFocused ? COLOR_SEARCH_FOCUSED : (hovered ? 0xFF302E2A : COLOR_SEARCH_BG);
            guiGraphics.fill(searchFieldX, searchFieldY, searchFieldX + SEARCH_FIELD_WIDTH, searchFieldY + SEARCH_FIELD_HEIGHT, bg);
            String shown = searchText.isEmpty() && !searchFocused ? "Search..." : searchText + (searchFocused ? "_" : "");
            int color = searchText.isEmpty() && !searchFocused ? COLOR_COUNT : COLOR_TEXT;
            guiGraphics.text(this.font, shown, searchFieldX + 3, searchFieldY + 2, color);
        }
    }

    // ---- Filtering (R10) --------------------------------------------------

    private List<LootBinGrouping.AggregatedEntry> filterBySearch(List<LootBinGrouping.AggregatedEntry> entries) {
        if (!showSearchBar() || searchText.isEmpty()) {
            return entries;
        }
        String needle = searchText.toLowerCase(Locale.ROOT);
        List<LootBinGrouping.AggregatedEntry> out = new ArrayList<>();
        for (LootBinGrouping.AggregatedEntry entry : entries) {
            if (matchesSearch(entry.key().representative(), needle)) {
                out.add(entry);
            }
        }
        return out;
    }

    private boolean matchesSearch(ItemStack stack, String needleLower) {
        if (LootBinGrouping.displayName(stack).toLowerCase(Locale.ROOT).contains(needleLower)) {
            return true;
        }
        List<Component> tooltip = stack.getTooltipLines(
                Item.TooltipContext.of(this.minecraft.level), this.minecraft.player, TooltipFlag.NORMAL);
        for (Component line : tooltip) {
            if (line.getString().toLowerCase(Locale.ROOT).contains(needleLower)) {
                return true;
            }
        }
        return false;
    }

    // ---- Click handling (R11-R13) -----------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!groupedViewActive) {
            return super.mouseClicked(event, doubleClick);
        }
        double mouseX = event.x();
        double mouseY = event.y();

        if (showSearchBar() && mouseX >= searchFieldX && mouseX <= searchFieldX + SEARCH_FIELD_WIDTH
                && mouseY >= searchFieldY && mouseY <= searchFieldY + SEARCH_FIELD_HEIGHT) {
            searchFocused = true;
            return true;
        }
        if (mouseY >= dividerTop && mouseY < dividerTop + DIVIDER_HEIGHT) {
            searchFocused = false;
            return true;
        }
        if (mouseY >= listTop && mouseY < listTop + listHeight
                && mouseX >= this.leftPos && mouseX < this.leftPos + this.imageWidth) {
            searchFocused = false;
            return handleListClick(mouseX, mouseY, event.button(), event.hasShiftDown());
        }
        searchFocused = false;
        return super.mouseClicked(event, doubleClick);
    }

    private boolean handleListClick(double mouseX, double mouseY, int button, boolean shiftDown) {
        if (button != 0 && button != 1) {
            return true; // Non-goals: no other buttons handled in grouped mode
        }
        List<LootBinGrouping.GroupResult> groups = LootBinGrouping.computeGroups(
                this.menu.slots, this.playerInventory, groupOrder(), sortWithinGroup());
        int rowY = listTop - scrollOffset;
        for (LootBinGrouping.GroupResult group : groups) {
            List<LootBinGrouping.AggregatedEntry> visible = filterBySearch(group.entries());
            if (visible.isEmpty()) {
                continue;
            }
            rowY += HEADER_HEIGHT;
            for (LootBinGrouping.AggregatedEntry entry : visible) {
                if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                    dispatchClick(entry, button, shiftDown);
                    return true;
                }
                rowY += ROW_HEIGHT;
            }
        }
        return true;
    }

    /** R11's hard invariant: exactly one real-slot {@link #slotClicked} call per gesture. */
    private void dispatchClick(LootBinGrouping.AggregatedEntry entry, int button, boolean shiftDown) {
        List<LootBinGrouping.SlotCandidate> candidates = new ArrayList<>();
        for (LootBinGrouping.RealSlot real : entry.backingSlots()) {
            candidates.add(new LootBinGrouping.SlotCandidate(
                    this.menu.slots.indexOf(real.slot()), real.stack().getCount()));
        }
        int slotIndex = LootBinGrouping.chooseSlot(candidates);
        if (slotIndex < 0) {
            return;
        }
        Slot targetSlot = this.menu.slots.get(slotIndex);

        if (shiftDown) {
            this.slotClicked(targetSlot, slotIndex, 0, ContainerInput.QUICK_MOVE);
        } else if (button == 1) {
            this.slotClicked(targetSlot, slotIndex, 1, ContainerInput.PICKUP);
        } else {
            this.slotClicked(targetSlot, slotIndex, 0, ContainerInput.PICKUP);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (groupedViewActive && mouseY >= listTop && mouseY < listTop + listHeight
                && mouseX >= this.leftPos && mouseX < this.leftPos + this.imageWidth) {
            scrollOffset -= (int) Math.round(verticalAmount * SCROLL_STEP);
            if (scrollOffset < 0) {
                scrollOffset = 0;
            }
            int maxScroll = Math.max(0, lastContentBottom - listTop - listHeight);
            if (scrollOffset > maxScroll) {
                scrollOffset = maxScroll;
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (groupedViewActive && searchFocused && showSearchBar()) {
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                if (!searchText.isEmpty()) {
                    searchText = searchText.substring(0, searchText.length() - 1);
                    scrollOffset = 0;
                }
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                searchFocused = false;
                return true;
            }
            return true; // swallow other keys while typing (avoid accidental hotbar-swap/close)
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (groupedViewActive && searchFocused && showSearchBar()) {
            char c = (char) event.codepoint();
            if (searchText.length() < 64 && c >= 32 && c != 127) {
                searchText += c;
                scrollOffset = 0;
            }
            return true;
        }
        return super.charTyped(event);
    }

    // ---- R15: secondary hotkey polling -------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        TweaksKeyBindings keyBindings = LootBinScreenRegistration.keyBindings();
        KeyMapping secondary = keyBindings != null ? keyBindings.secondaryKeyBindingOf(TweakId.LOOT_BIN) : null;
        if (secondary != null && secondary.consumeClick()) {
            groupedViewActive = !groupedViewActive;
        }
    }

    // ---- Configurable accessors --------------------------------------------

    private TweakHooksImpl hooks() {
        return LootBinScreenRegistration.hooks();
    }

    private boolean showSearchBar() {
        return !Boolean.FALSE.equals(hooks().lootBinConfigurable("showSearchBar"));
    }

    private String groupOrder() {
        Object raw = hooks().lootBinConfigurable("groupOrder");
        return raw != null ? raw.toString() : "CREATIVE_TAB_ORDER";
    }

    private String sortWithinGroup() {
        Object raw = hooks().lootBinConfigurable("sortWithinGroup");
        return raw != null ? raw.toString() : "CREATIVE_ORDER";
    }
}
