package de.lazuli.tweaks;

import de.lazuli.api.tweaks.TweakId;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loot Bin UI (docs/specs/tweaks-loot-bin-ui.md) replacement container
 * screen -- Yarn (1.21.11) port of {@code fabric-26.1}/{@code fabric-26.2}'s
 * copy of this file (same per-platform-file duplication convention as
 * {@code FreecamCameraEntity}/{@code FreecamTicker}). See the Mojmap copies
 * for the full rendering/click-routing rationale; only type/method names
 * differ here (e.g. {@code HandledScreen}/{@code onMouseClick}/{@code
 * SlotActionType} instead of {@code AbstractContainerScreen}/{@code
 * slotClicked}/{@code ContainerInput}, {@code x}/{@code y} instead of
 * {@code leftPos}/{@code topPos}, {@code Click} instead of {@code
 * MouseButtonEvent}).
 */
public final class LootBinScreen<T extends ScreenHandler> extends HandledScreen<T> {

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

    public LootBinScreen(T handler, Inventory playerInventory, Text title) {
        super(handler, castToPlayerInventory(playerInventory), title);
        this.playerInventory = playerInventory;
        this.backgroundWidth = PANEL_WIDTH;
        this.backgroundHeight = computeHeight(handler, playerInventory);
    }

    /**
     * {@code HandledScreen}'s single constructor requires the Yarn-specific
     * {@code PlayerInventory} subtype (not the plain {@code Inventory}
     * interface {@code MenuScreens}-equivalent registration hands us); every
     * real container-open always passes a real {@code PlayerInventory}
     * instance here (confirmed by {@code HandledScreens.Provider}'s own
     * {@code create(T, PlayerInventory, Text)} signature), so this cast is
     * always safe in practice.
     */
    private static net.minecraft.entity.player.PlayerInventory castToPlayerInventory(Inventory inventory) {
        return (net.minecraft.entity.player.PlayerInventory) inventory;
    }

    private static int computeHeight(ScreenHandler handler, Inventory playerInventory) {
        LootBinGrouping.PlayerInvBounds bounds = LootBinGrouping.playerInvBounds(handler.slots, playerInventory);
        int listAreaHeight = Math.max(60, bounds.top() - TOP_MARGIN - GAP_ABOVE_DIVIDER - DIVIDER_HEIGHT);
        return TOP_MARGIN + listAreaHeight + GAP_ABOVE_DIVIDER + DIVIDER_HEIGHT
                + (bounds.bottom() - bounds.top()) + BOTTOM_MARGIN;
    }

    @Override
    protected void init() {
        super.init();
        LootBinGrouping.PlayerInvBounds bounds = LootBinGrouping.playerInvBounds(this.handler.slots, this.playerInventory);
        listTop = this.y + TOP_MARGIN;
        dividerTop = this.y + bounds.top() - GAP_ABOVE_DIVIDER - DIVIDER_HEIGHT;
        listHeight = Math.max(20, dividerTop - listTop);
        searchFieldX = this.x + 6;
        searchFieldY = dividerTop + (DIVIDER_HEIGHT - SEARCH_FIELD_HEIGHT) / 2;
    }

    // ---- Rendering -------------------------------------------------------

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        // Deliberately empty: R8's custom minimal panel replaces vanilla's own
        // stretched/tiled chest texture entirely -- drawn from render() below.
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!groupedViewActive) {
            super.render(context, mouseX, mouseY, delta);
            renderModeLabel(context);
            return;
        }

        context.fill(this.x, this.y, this.x + this.backgroundWidth, this.y + this.backgroundHeight, COLOR_PANEL_BG);
        drawBorder(context, this.x, this.y, this.backgroundWidth, this.backgroundHeight);
        context.drawText(this.textRenderer, this.title, this.x + 6, this.y + 6, COLOR_HEADER, false);
        renderModeLabel(context);

        renderGroupedList(context, mouseX, mouseY);
        renderDivider(context, mouseX, mouseY);

        // R9: player-inventory region -- reuse the exact vanilla per-slot draw call,
        // unmodified, only for real slots backed by the player's own inventory.
        for (Slot slot : this.handler.slots) {
            if (LootBinGrouping.isPlayerSlot(slot, this.playerInventory)) {
                this.drawSlot(context, slot, mouseX, mouseY);
                maybeShowSlotTooltip(context, slot, mouseX, mouseY);
            }
        }
        this.renderCursorStack(context, mouseX, mouseY);
    }

    private void maybeShowSlotTooltip(DrawContext context, Slot slot, int mouseX, int mouseY) {
        int sx = this.x + slot.x;
        int sy = this.y + slot.y;
        if (mouseX >= sx && mouseX < sx + ICON_SIZE && mouseY >= sy && mouseY < sy + ICON_SIZE && !slot.getStack().isEmpty()) {
            context.drawTooltip(this.textRenderer, getTooltipFromItem(slot.getStack()), mouseX, mouseY);
        }
    }

    private void renderModeLabel(DrawContext context) {
        String label = groupedViewActive ? "Grouped" : "Vanilla Grid";
        int width = this.textRenderer.getWidth(label);
        context.drawText(this.textRenderer, label, this.x + this.backgroundWidth - width - 6, this.y + 6, COLOR_MODE_LABEL, false);
    }

    private static void drawBorder(DrawContext context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + 1, COLOR_BORDER);
        context.fill(x, y + height - 1, x + width, y + height, COLOR_BORDER);
        context.fill(x, y, x + 1, y + height, COLOR_BORDER);
        context.fill(x + width - 1, y, x + width, y + height, COLOR_BORDER);
    }

    private void renderGroupedList(DrawContext context, int mouseX, int mouseY) {
        context.enableScissor(this.x, listTop, this.x + this.backgroundWidth, listTop + listHeight);

        List<LootBinGrouping.GroupResult> groups = LootBinGrouping.computeGroups(
                this.handler.slots, this.playerInventory, groupOrder(), sortWithinGroup());

        int rowY = listTop - scrollOffset;
        int leftEdge = this.x + 6;
        int rightEdge = this.x + this.backgroundWidth - 6;

        for (LootBinGrouping.GroupResult group : groups) {
            List<LootBinGrouping.AggregatedEntry> visible = filterBySearch(group.entries());
            if (visible.isEmpty()) {
                continue;
            }
            if (rowY + HEADER_HEIGHT > listTop && rowY < listTop + listHeight) {
                context.drawText(this.textRenderer, group.tab().getDisplayName(), leftEdge, rowY + 2, COLOR_HEADER, false);
            }
            rowY += HEADER_HEIGHT;
            for (LootBinGrouping.AggregatedEntry entry : visible) {
                if (rowY + ROW_HEIGHT > listTop && rowY < listTop + listHeight) {
                    renderEntryRow(context, entry, leftEdge, rightEdge, rowY, mouseX, mouseY);
                }
                rowY += ROW_HEIGHT;
            }
        }
        lastContentBottom = rowY + scrollOffset;
        context.disableScissor();
    }

    private void renderEntryRow(DrawContext context, LootBinGrouping.AggregatedEntry entry,
            int leftEdge, int rightEdge, int rowY, int mouseX, int mouseY) {
        boolean hovered = mouseX >= leftEdge - 4 && mouseX <= rightEdge + 4 && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                && mouseY >= listTop && mouseY < listTop + listHeight;
        if (hovered) {
            context.fill(leftEdge - 4, rowY, rightEdge + 4, rowY + ROW_HEIGHT, COLOR_ROW_HOVER);
        }
        ItemStack representative = entry.key().representative();
        ItemStack icon = representative.copyWithCount(1);
        context.drawItem(icon, leftEdge, rowY + 1);

        String name = LootBinGrouping.displayName(representative);
        context.drawText(this.textRenderer, name, leftEdge + ICON_SIZE + 4, rowY + 5, COLOR_TEXT, false);

        String countText = formatCount(entry);
        int countWidth = this.textRenderer.getWidth(countText);
        context.drawText(this.textRenderer, countText, rightEdge - countWidth, rowY + 5, COLOR_COUNT, false);
    }

    private String formatCount(LootBinGrouping.AggregatedEntry entry) {
        if ("WITH_STACK_HINT".equals(hooks().lootBinConfigurable("countTextStyle"))) {
            return entry.totalCount() + " (" + entry.stackCount() + " stack" + (entry.stackCount() == 1 ? "" : "s") + ")";
        }
        return String.valueOf(entry.totalCount());
    }

    private void renderDivider(DrawContext context, int mouseX, int mouseY) {
        int dividerBottom = dividerTop + DIVIDER_HEIGHT;
        context.fill(this.x, dividerTop, this.x + this.backgroundWidth, dividerBottom, COLOR_DIVIDER_BG);

        int[] fullness = LootBinGrouping.containerFullness(this.handler.slots, this.playerInventory);
        String fullnessText = LootBinGrouping.fullnessText(fullness[0], fullness[1]);
        int fw = this.textRenderer.getWidth(fullnessText);
        context.drawText(this.textRenderer, fullnessText, this.x + this.backgroundWidth - 6 - fw,
                dividerTop + (DIVIDER_HEIGHT - 8) / 2, COLOR_COUNT, false);

        if (showSearchBar()) {
            boolean hovered = mouseX >= searchFieldX && mouseX <= searchFieldX + SEARCH_FIELD_WIDTH
                    && mouseY >= searchFieldY && mouseY <= searchFieldY + SEARCH_FIELD_HEIGHT;
            int bg = searchFocused ? COLOR_SEARCH_FOCUSED : (hovered ? 0xFF302E2A : COLOR_SEARCH_BG);
            context.fill(searchFieldX, searchFieldY, searchFieldX + SEARCH_FIELD_WIDTH, searchFieldY + SEARCH_FIELD_HEIGHT, bg);
            String shown = searchText.isEmpty() && !searchFocused ? "Search..." : searchText + (searchFocused ? "_" : "");
            int color = searchText.isEmpty() && !searchFocused ? COLOR_COUNT : COLOR_TEXT;
            context.drawText(this.textRenderer, shown, searchFieldX + 3, searchFieldY + 2, color, false);
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
        List<Text> tooltip = stack.getTooltip(
                Item.TooltipContext.create(this.client.world), this.client.player, TooltipType.BASIC);
        for (Text line : tooltip) {
            if (line.getString().toLowerCase(Locale.ROOT).contains(needleLower)) {
                return true;
            }
        }
        return false;
    }

    // ---- Click handling (R11-R13) -----------------------------------------

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (!groupedViewActive) {
            return super.mouseClicked(click, doubleClick);
        }
        double mouseX = click.x();
        double mouseY = click.y();

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
                && mouseX >= this.x && mouseX < this.x + this.backgroundWidth) {
            searchFocused = false;
            return handleListClick(mouseX, mouseY, click.button(), click.hasShift());
        }
        searchFocused = false;
        return super.mouseClicked(click, doubleClick);
    }

    private boolean handleListClick(double mouseX, double mouseY, int button, boolean shiftDown) {
        if (button != 0 && button != 1) {
            return true; // Non-goals: no other buttons handled in grouped mode
        }
        List<LootBinGrouping.GroupResult> groups = LootBinGrouping.computeGroups(
                this.handler.slots, this.playerInventory, groupOrder(), sortWithinGroup());
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

    /** R11's hard invariant: exactly one real-slot {@link #onMouseClick(Slot, int, int, SlotActionType)} call per gesture. */
    private void dispatchClick(LootBinGrouping.AggregatedEntry entry, int button, boolean shiftDown) {
        List<LootBinGrouping.SlotCandidate> candidates = new ArrayList<>();
        for (LootBinGrouping.RealSlot real : entry.backingSlots()) {
            candidates.add(new LootBinGrouping.SlotCandidate(
                    this.handler.slots.indexOf(real.slot()), real.stack().getCount()));
        }
        int slotIndex = LootBinGrouping.chooseSlot(candidates);
        if (slotIndex < 0) {
            return;
        }
        Slot targetSlot = this.handler.slots.get(slotIndex);

        if (shiftDown) {
            this.onMouseClick(targetSlot, slotIndex, 0, SlotActionType.QUICK_MOVE);
        } else if (button == 1) {
            this.onMouseClick(targetSlot, slotIndex, 1, SlotActionType.PICKUP);
        } else {
            this.onMouseClick(targetSlot, slotIndex, 0, SlotActionType.PICKUP);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (groupedViewActive && mouseY >= listTop && mouseY < listTop + listHeight
                && mouseX >= this.x && mouseX < this.x + this.backgroundWidth) {
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
    public boolean keyPressed(KeyInput input) {
        if (groupedViewActive && searchFocused && showSearchBar()) {
            if (input.key() == GLFW.GLFW_KEY_BACKSPACE) {
                if (!searchText.isEmpty()) {
                    searchText = searchText.substring(0, searchText.length() - 1);
                    scrollOffset = 0;
                }
                return true;
            }
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
                searchFocused = false;
                return true;
            }
            return true; // swallow other keys while typing (avoid accidental hotbar-swap/close)
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (groupedViewActive && searchFocused && showSearchBar()) {
            char c = (char) input.codepoint();
            if (searchText.length() < 64 && c >= 32 && c != 127) {
                searchText += c;
                scrollOffset = 0;
            }
            return true;
        }
        return super.charTyped(input);
    }

    // ---- R15: secondary hotkey polling -------------------------------------

    @Override
    protected void handledScreenTick() {
        super.handledScreenTick();
        TweaksKeyBindings keyBindings = LootBinScreenRegistration.keyBindings();
        KeyBinding secondary = keyBindings != null ? keyBindings.secondaryKeyBindingOf(TweakId.LOOT_BIN) : null;
        if (secondary != null && secondary.wasPressed()) {
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
