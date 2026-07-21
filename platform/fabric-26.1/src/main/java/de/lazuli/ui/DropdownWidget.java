package de.lazuli.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;

/**
 * Generic, reusable, hand-drawn dropdown/list-popup control
 * ({@code platform/ui/specification.md}, UI-FR1-UI-FR7) -- Mojang-mapped
 * (26.x) render idiom, {@code fabric-26.1} structural twin. Not a
 * {@link net.minecraft.client.gui.components.AbstractWidget} itself (UI-FR4's
 * "may be a plain helper object" allowance) -- composed directly inside an
 * embedding widget's own manually-driven {@code renderNow()}/
 * {@code mouseClicked()} methods (e.g. {@code de.lazuli.friends.FriendSidebarWidget}),
 * matching the hand-drawn, no-vanilla-widget-pipeline convention that
 * embedding widget already established.
 *
 * <p>Contains zero import of any feature's {@code api}/{@code services}
 * types (UI-FR6) -- operates purely on caller-supplied {@link Option} label/
 * description pairs and a plain {@code int} selected index.
 */
public final class DropdownWidget {

    private static final int BACKGROUND = 0xCC202020;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int ROW_PADDING = 3;
    private static final int DESCRIPTION_LINE_HEIGHT = 10;

    public record Option(String label, String description) {}

    private final List<Option> options;
    private final IntConsumer onSelectionChanged;
    private int selectedIndex;
    private boolean open;

    public DropdownWidget(List<Option> options, int initialSelectedIndex, IntConsumer onSelectionChanged) {
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
     * Renders the closed-state row (always) plus, while {@link #isOpen()},
     * every option row stacked directly beneath it (UI-FR2). Each open-state
     * row's description is revealed only while the mouse hovers that
     * specific row (matching the closed-state control's own existing
     * hover-reveals-description convention).
     *
     * @return the total height, in pixels, this control consumed this frame
     *         (row height while closed, row height * (1 + options.size())
     *         while open) -- the caller's own layout (e.g.
     *         {@code listTopOffset()}) must use this value (v1.4-FR7.14).
     */
    public int render(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int rowHeight, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        Option selected = options.get(selectedIndex);
        guiGraphics.fill(x, y, x + width, y + rowHeight, BACKGROUND);
        guiGraphics.text(font, selected.label(), x + ROW_PADDING, y + ROW_PADDING, TEXT_COLOR);

        if (!open) {
            return rowHeight;
        }

        int rowY = y + rowHeight;
        for (Option option : options) {
            boolean overRow = mouseX >= x && mouseX < x + width && mouseY >= rowY && mouseY < rowY + rowHeight;
            guiGraphics.fill(x, rowY, x + width, rowY + rowHeight, BACKGROUND);
            guiGraphics.text(font, option.label(), x + ROW_PADDING, rowY + ROW_PADDING, TEXT_COLOR);
            if (overRow && option.description() != null && !option.description().isEmpty()) {
                int textY = rowY + rowHeight + ROW_PADDING;
                int maxTextWidth = width - ROW_PADDING * 2;
                for (String line : wrapMessage(font::width, option.description(), maxTextWidth)) {
                    guiGraphics.text(font, line, x + ROW_PADDING, textY, TEXT_COLOR);
                    textY += DESCRIPTION_LINE_HEIGHT;
                }
            }
            rowY += rowHeight;
        }
        return rowHeight * (1 + options.size());
    }

    /**
     * Feeds a click at {@code (mouseX, mouseY)} to this control, hit-tested
     * against the same {@code (x, y, width, rowHeight)} the caller last
     * rendered at this frame.
     *
     * @return {@code true} if this click was consumed by this control
     *         (closed-row toggle, option selection, or an "elsewhere within
     *         the caller's own bounds while open" close) -- {@code false} if
     *         the caller should continue its own click handling.
     */
    public boolean mouseClicked(double mouseX, double mouseY, int x, int y, int width, int rowHeight) {
        boolean overClosedRow = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + rowHeight;
        if (overClosedRow) {
            open = !open;
            return true;
        }
        if (!open) {
            return false;
        }
        int rowY = y + rowHeight;
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

    private static List<String> wrapMessage(ToIntFunction<String> widthOf, String message, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : message.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && widthOf.applyAsInt(candidate) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }
}
