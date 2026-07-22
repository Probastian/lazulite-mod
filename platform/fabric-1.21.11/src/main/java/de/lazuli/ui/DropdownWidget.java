package de.lazuli.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Generic, reusable, hand-drawn dropdown/list-popup control
 * ({@code platform/ui/specification.md}, UI-FR1-UI-FR18) -- Yarn-mapped
 * (1.21.11) render idiom, structural twin of the Mojang-mapped copies in
 * {@code fabric-26.1}/{@code fabric-26.2}. Not a
 * {@link net.minecraft.client.gui.widget.ClickableWidget} itself (UI-FR4's
 * "may be a plain helper object" allowance) -- composed directly inside an
 * embedding widget's own manually-driven {@code renderNow()}/
 * {@code mouseClicked()} methods (e.g. {@code de.lazuli.friends.FriendSidebarWidget}),
 * matching the hand-drawn, no-vanilla-widget-pipeline convention that
 * embedding widget already established.
 *
 * <p>Contains zero import of any feature's {@code api}/{@code services}
 * types (UI-FR6) -- operates purely on caller-supplied {@link Option} label/
 * description pairs and a plain {@code int} selected index.
 *
 * <p>v2 ("Polish pass") amendment (UI-FR8-UI-FR18): {@link #render} now draws
 * only the optional label plus the closed row (with button chrome/hover
 * highlight and a native tooltip for the closed row's own description, if
 * any) and caches the bounds/options used to draw it; the open-state option
 * rows are drawn separately by {@link #renderOpenOverlay}, using those cached
 * bounds, so a caller can draw the overlay at a different z-order pass than
 * the closed row (UI-FR15/UI-FR16).
 */
public final class DropdownWidget {

    // Closed row ("button") background -- kept semi-transparent and given a
    // distinctly bluer/more saturated tint than the (now fully opaque, see
    // OPTION_BACKGROUND below) open-state option rows, so the button reads
    // as its own kind of control rather than as just another list row (UI
    // polish pass point 4). The transparency itself also helps it read as
    // "floating" chrome rather than a solid list surface.
    private static final int BACKGROUND = 0xCC2B3A55;
    private static final int BACKGROUND_HOVER = 0xCC3C4F73;

    // Open-state option-row background -- fully opaque (alpha 0xFF, no
    // transparency at all) per UI polish pass point 3, and a plain neutral
    // grey (as opposed to the button's blue tint) so the expanded value list
    // reads as visually distinct from the closed-row button (point 4).
    private static final int OPTION_BACKGROUND = 0xFF202020;
    private static final int OPTION_BACKGROUND_HOVER = 0xFF3A3A3A;

    private static final int BORDER_COLOR = 0xFF8A8A8A;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int ROW_PADDING = 3;
    private static final int LABEL_HEIGHT = 10;
    private static final String ARROW_GLYPH = "▼";

    public record Option(String label, String description) {}

    private final String label;
    private final List<Option> options;
    private final IntConsumer onSelectionChanged;
    private int selectedIndex;
    private boolean open;

    // UI-FR16: bounds/options cached from the most recent render() call, used
    // by renderOpenOverlay() so both methods draw at identical coordinates
    // within the same frame.
    private int cachedX;
    private int cachedY;
    private int cachedWidth;
    private int cachedRowHeight;

    public DropdownWidget(List<Option> options, int initialSelectedIndex, IntConsumer onSelectionChanged) {
        this(null, options, initialSelectedIndex, onSelectionChanged);
    }

    public DropdownWidget(String label, List<Option> options, int initialSelectedIndex, IntConsumer onSelectionChanged) {
        this.label = label;
        this.options = List.copyOf(options);
        this.selectedIndex = initialSelectedIndex;
        this.onSelectionChanged = onSelectionChanged;
    }

    public boolean isOpen() {
        return open;
    }

    /** v1.4-FR7.6a hook -- closes without changing the selection. */
    public void close() {
        open = false;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    /**
     * Renders the optional label (if present) plus the closed-state row only
     * (UI-FR15 item 1) -- button chrome/border (UI-FR8), a hover highlight
     * when the closed row is hovered (UI-FR9), and, if hovered and the
     * selected option has a non-empty description, a native tooltip
     * (UI-FR12/UI-FR13). Never draws open-state option rows, regardless of
     * {@link #isOpen()} -- see {@link #renderOpenOverlay}.
     *
     * <p>Caches {@code (x, y, width, rowHeight)} for {@link #renderOpenOverlay}
     * before returning (UI-FR16).
     *
     * @return the height, in pixels, this call consumed this frame -- the
     *         label row's height (if a label is set) plus the closed row's
     *         height; never includes the open-state rows (UI-FR11).
     */
    public int render(DrawContext context, int x, int y, int width, int rowHeight, int mouseX, int mouseY) {
        var textRenderer = MinecraftClient.getInstance().textRenderer;
        int rowY = y;
        int totalHeight = 0;
        if (label != null && !label.isEmpty()) {
            context.drawTextWithShadow(textRenderer, label, x + ROW_PADDING, rowY, TEXT_COLOR);
            rowY += LABEL_HEIGHT;
            totalHeight += LABEL_HEIGHT;
        }

        // Cache the already-offset closed-row Y (not the raw y parameter) so
        // renderOpenOverlay()'s cachedY + cachedRowHeight math lands exactly
        // where the closed row was actually drawn, without needing to know
        // about the label offset itself.
        cachedX = x;
        cachedY = rowY;
        cachedWidth = width;
        cachedRowHeight = rowHeight;

        Option selected = options.get(selectedIndex);
        boolean overClosedRow = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowHeight;
        context.fill(x, rowY, x + width, rowY + rowHeight, overClosedRow ? BACKGROUND_HOVER : BACKGROUND);
        drawBorder(context, x, rowY, width, rowHeight);
        context.drawTextWithShadow(textRenderer, selected.label(), x + ROW_PADDING, rowY + ROW_PADDING, TEXT_COLOR);
        // UI polish pass point 2: a down-arrow glyph, right-aligned within
        // the closed row, so the control visually reads as a dropdown rather
        // than a plain button -- closed row only, never drawn on the open
        // option rows in renderOpenOverlay() (those aren't themselves
        // dropdowns).
        context.drawTextWithShadow(textRenderer, ARROW_GLYPH, x + width - ROW_PADDING - textRenderer.getWidth(ARROW_GLYPH),
                rowY + ROW_PADDING, TEXT_COLOR);
        if (overClosedRow && selected.description() != null && !selected.description().isEmpty()) {
            context.drawTooltip(Text.literal(selected.description()), mouseX, mouseY);
        }
        totalHeight += rowHeight;
        return totalHeight;
    }

    /**
     * Draws every open-state option row (UI-FR15 item 2), stacked directly
     * beneath the closed row's bounds cached by the most recent
     * {@link #render} call this frame (UI-FR16) -- chrome/border, hover
     * highlight, and a native tooltip for whichever row is currently
     * hovered. A no-op (zero draw calls) while {@link #isOpen()} is
     * {@code false}.
     */
    public void renderOpenOverlay(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!open) {
            return;
        }
        var textRenderer = MinecraftClient.getInstance().textRenderer;
        int rowY = cachedY + cachedRowHeight;
        for (Option option : options) {
            boolean overRow = mouseX >= cachedX && mouseX < cachedX + cachedWidth
                    && mouseY >= rowY && mouseY < rowY + cachedRowHeight;
            context.fill(cachedX, rowY, cachedX + cachedWidth, rowY + cachedRowHeight, overRow ? OPTION_BACKGROUND_HOVER : OPTION_BACKGROUND);
            drawBorder(context, cachedX, rowY, cachedWidth, cachedRowHeight);
            context.drawTextWithShadow(textRenderer, option.label(), cachedX + ROW_PADDING, rowY + ROW_PADDING, TEXT_COLOR);
            if (overRow && option.description() != null && !option.description().isEmpty()) {
                context.drawTooltip(Text.literal(option.description()), mouseX, mouseY);
            }
            rowY += cachedRowHeight;
        }
    }

    private static void drawBorder(DrawContext context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + 1, BORDER_COLOR);
        context.fill(x, y + height - 1, x + width, y + height, BORDER_COLOR);
        context.fill(x, y, x + 1, y + height, BORDER_COLOR);
        context.fill(x + width - 1, y, x + width, y + height, BORDER_COLOR);
    }

    /**
     * The vertical space consumed by the optional label above the closed
     * row, if one is set -- {@code render()}'s only caller-invisible offset
     * (UI-FR16); computed here too so {@link #mouseClicked} hit-tests
     * against the exact same effective row-start Y that {@link #render}
     * actually drew the closed row at, without requiring the caller to
     * pre-adjust {@code y} itself.
     */
    private int labelHeight() {
        return (label != null && !label.isEmpty()) ? LABEL_HEIGHT : 0;
    }

    /**
     * Feeds a click at {@code (mouseX, mouseY)} to this control, hit-tested
     * against the same {@code (x, y, width, rowHeight)} the caller last
     * rendered at this frame -- {@code y} is the raw, pre-label value (same
     * as passed to {@link #render}); the label's own height, if any, is
     * accounted for internally via {@link #labelHeight()} so the closed
     * row's hit-test lines up with where it was actually drawn.
     *
     * @return {@code true} if this click was consumed by this control
     *         (closed-row toggle, option selection, or an "elsewhere within
     *         the caller's own bounds while open" close) -- {@code false} if
     *         the caller should continue its own click handling.
     */
    public boolean mouseClicked(double mouseX, double mouseY, int x, int y, int width, int rowHeight) {
        int closedRowY = y + labelHeight();
        boolean overClosedRow = mouseX >= x && mouseX < x + width && mouseY >= closedRowY && mouseY < closedRowY + rowHeight;
        if (overClosedRow) {
            open = !open;
            return true;
        }
        if (!open) {
            return false;
        }
        int rowY = closedRowY + rowHeight;
        for (int i = 0; i < options.size(); i++) {
            boolean overRow = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowHeight;
            if (overRow) {
                selectedIndex = i;
                open = false;
                onSelectionChanged.accept(i);
                return true;
            }
            rowY += rowHeight;
        }
        // Elsewhere within the caller's own bounds while open -- close
        // without changing the selection (UI-FR3).
        open = false;
        return true;
    }
}
