package de.lazuli.mainmenu;

import de.lazuli.api.waypoints.Waypoint;
import de.lazuli.api.waypoints.WaypointScopeResolver;
import de.lazuli.features.waypoints.services.WaypointRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Waypoint Manager panel (spec R19-R22): opened from a "Waypoints" button on
 * {@link PausePanel}, following {@code TweaksPanel}'s established sub-view-
 * swap shape (row list &lt;-&gt; a nullable sub-view field) and {@code
 * AchievementsPanel}'s pill-row/row-list visual idiom.
 *
 * <p>Constructor takes the feature's {@link WaypointRegistry} plus its
 * {@link WaypointScopeResolver} (for "current dimension"/"current position"
 * default behavior, R20/R21) -- live player position for the "Add at
 * current position" flow is obtained directly via {@code
 * Minecraft.getInstance()}, the same way other platform-side panels reach
 * Minecraft state directly rather than through the {@code api} layer, since
 * this is platform code.
 */
public final class WaypointManagerPanel {

    private static final int CONTENT_LEFT_PAD = 8;
    private static final int PILL_HEIGHT = 16;
    private static final int PILL_GAP = 6;
    private static final int ROW_HEIGHT = 28;
    private static final int SCROLL_STEP = 16;
    private static final int FORM_ROW_HEIGHT = 20;
    private static final int SWATCH_SIZE = 10;

    private static final int COLOR_ROW_IDLE = 0xFF201E17;
    private static final int COLOR_ROW_HOVER = 0xFF2A2820;
    private static final int COLOR_TITLE = 0xFFEAE8E1;
    private static final int COLOR_DESC = 0xFF908C7F;
    private static final int COLOR_ACCENT = 0xFFC9A227;
    private static final int COLOR_BORDER = 0xFF141210;
    private static final int COLOR_ARMED_BG = 0xFF6B2A2A;
    private static final int COLOR_CONFIRM_BG = 0xFF528A54;

    private enum SubView { ADD_HERE, ADD_MANUAL, EDIT }

    private final WaypointRegistry registry;
    private final WaypointScopeResolver scopeResolver;

    private String selectedDimension;
    private int scrollOffset;
    private String armedDeleteId;

    private SubView subView;
    private String editingWaypointId;
    private String formDimension;
    private EditBox nameBox;
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;

    private Consumer<AbstractWidget> addWidget;
    private Consumer<AbstractWidget> removeWidget;

    public WaypointManagerPanel(WaypointRegistry registry, WaypointScopeResolver scopeResolver) {
        this.registry = registry;
        this.scopeResolver = scopeResolver;
    }

    public void init(Consumer<AbstractWidget> addWidget, Consumer<AbstractWidget> removeWidget) {
        this.addWidget = addWidget;
        this.removeWidget = removeWidget;
    }

    /** Torn down by {@code PausePanel} when leaving this sub-view / on tab switch / on screen close. */
    public void leave() {
        closeSubView();
        armedDeleteId = null;
    }

    // ---- Dimension selection -------------------------------------------

    private List<String> dimensionOptions() {
        Set<String> ids = new LinkedHashSet<>(registry.knownDimensions());
        String current = scopeResolver.currentDimensionId();
        if (current != null) {
            ids.add(current);
        }
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        return sorted;
    }

    private String activeDimension() {
        List<String> options = dimensionOptions();
        if (selectedDimension != null && options.contains(selectedDimension)) {
            return selectedDimension;
        }
        String current = scopeResolver.currentDimensionId();
        if (current != null) {
            return current;
        }
        return options.isEmpty() ? null : options.get(0);
    }

    // ---- Rendering -------------------------------------------------------

    public void render(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        if (subView != null) {
            renderSubView(guiGraphics, font, x, y, width, height, mouseX, mouseY);
            return;
        }
        renderList(guiGraphics, font, x, y, width, height, mouseX, mouseY);
    }

    private void renderList(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int leftX = x + CONTENT_LEFT_PAD;
        guiGraphics.text(font, Component.literal("Waypoints"), leftX, y, COLOR_TITLE);

        List<String> options = dimensionOptions();
        String active = activeDimension();
        int pillY = y + 16;
        int pillX = leftX;
        for (String dimensionId : options) {
            String label = shortDimensionLabel(dimensionId);
            int pillWidth = font.width(label) + 16;
            boolean isActive = dimensionId.equals(active);
            boolean hovered = mouseX >= pillX && mouseX <= pillX + pillWidth && mouseY >= pillY && mouseY <= pillY + PILL_HEIGHT;
            guiGraphics.fill(pillX, pillY, pillX + pillWidth, pillY + PILL_HEIGHT,
                    isActive ? COLOR_CONFIRM_BG : (hovered ? COLOR_ROW_HOVER : COLOR_ROW_IDLE));
            guiGraphics.centeredText(font, Component.literal(label), pillX + pillWidth / 2, pillY + 4, COLOR_TITLE);
            pillX += pillWidth + PILL_GAP;
        }

        int addRowY = pillY + PILL_HEIGHT + 8;
        boolean canAddHere = active != null && active.equals(scopeResolver.currentDimensionId());
        int addHereWidth = font.width("+ Add at current position") + 16;
        boolean addHereHovered = canAddHere && mouseX >= leftX && mouseX <= leftX + addHereWidth
                && mouseY >= addRowY && mouseY <= addRowY + PILL_HEIGHT;
        guiGraphics.fill(leftX, addRowY, leftX + addHereWidth, addRowY + PILL_HEIGHT,
                canAddHere ? (addHereHovered ? COLOR_ROW_HOVER : COLOR_ROW_IDLE) : COLOR_BORDER);
        guiGraphics.centeredText(font, Component.literal("+ Add at current position"), leftX + addHereWidth / 2, addRowY + 4,
                canAddHere ? COLOR_TITLE : COLOR_DESC);

        int addManualX = leftX + addHereWidth + PILL_GAP;
        int addManualWidth = font.width("+ Add manually") + 16;
        boolean addManualHovered = mouseX >= addManualX && mouseX <= addManualX + addManualWidth
                && mouseY >= addRowY && mouseY <= addRowY + PILL_HEIGHT;
        guiGraphics.fill(addManualX, addRowY, addManualX + addManualWidth, addRowY + PILL_HEIGHT, addManualHovered ? COLOR_ROW_HOVER : COLOR_ROW_IDLE);
        guiGraphics.centeredText(font, Component.literal("+ Add manually"), addManualX + addManualWidth / 2, addRowY + 4, COLOR_TITLE);

        int contentY = addRowY + PILL_HEIGHT + 10;
        int contentHeight = y + height - contentY;
        guiGraphics.enableScissor(x, contentY, x + width, contentY + contentHeight);
        List<Waypoint> waypoints = active == null ? List.of() : registry.list(active);
        if (waypoints.isEmpty()) {
            guiGraphics.text(font, Component.literal("No waypoints in this dimension yet."), leftX, contentY, COLOR_DESC);
        } else {
            int rowY = contentY - scrollOffset;
            for (Waypoint waypoint : waypoints) {
                renderRow(guiGraphics, font, x, width, rowY, waypoint, mouseX, mouseY);
                rowY += ROW_HEIGHT + 2;
            }
        }
        guiGraphics.disableScissor();
    }

    private void renderRow(GuiGraphicsExtractor guiGraphics, Font font, int x, int width, int rowY, Waypoint waypoint, int mouseX, int mouseY) {
        int rightEdge = x + width - CONTENT_LEFT_PAD;
        boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
        guiGraphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, hovered ? COLOR_ROW_HOVER : COLOR_ROW_IDLE);

        int swatchX = x + CONTENT_LEFT_PAD;
        int swatchY = rowY + (ROW_HEIGHT - SWATCH_SIZE) / 2;
        guiGraphics.fill(swatchX, swatchY, swatchX + SWATCH_SIZE, swatchY + SWATCH_SIZE, 0xFF000000 | (waypoint.color() & 0xFFFFFF));

        int textX = swatchX + SWATCH_SIZE + 6;
        guiGraphics.text(font, Component.literal(waypoint.name()), textX, rowY + 4, COLOR_TITLE);
        String coords = waypoint.x() + ", " + waypoint.y() + ", " + waypoint.z();
        guiGraphics.text(font, Component.literal(coords), textX, rowY + 15, COLOR_DESC);

        boolean armed = waypoint.id().equals(armedDeleteId);
        String deleteLabel = armed ? "Confirm?" : "Delete";
        int deleteWidth = font.width(deleteLabel) + 14;
        int deleteX = rightEdge - deleteWidth;
        int controlY = rowY + (ROW_HEIGHT - PILL_HEIGHT) / 2;
        boolean deleteHovered = mouseX >= deleteX && mouseX <= deleteX + deleteWidth && mouseY >= controlY && mouseY <= controlY + PILL_HEIGHT;
        guiGraphics.fill(deleteX, controlY, deleteX + deleteWidth, controlY + PILL_HEIGHT,
                armed ? COLOR_ARMED_BG : (deleteHovered ? COLOR_ROW_HOVER : COLOR_BORDER));
        guiGraphics.centeredText(font, Component.literal(deleteLabel), deleteX + deleteWidth / 2, controlY + 4, COLOR_ACCENT);

        String editLabel = "Edit";
        int editWidth = font.width(editLabel) + 14;
        int editX = deleteX - editWidth - PILL_GAP;
        boolean editHovered = mouseX >= editX && mouseX <= editX + editWidth && mouseY >= controlY && mouseY <= controlY + PILL_HEIGHT;
        guiGraphics.fill(editX, controlY, editX + editWidth, controlY + PILL_HEIGHT, editHovered ? COLOR_ROW_HOVER : COLOR_BORDER);
        guiGraphics.centeredText(font, Component.literal(editLabel), editX + editWidth / 2, controlY + 4, COLOR_ACCENT);
    }

    private void renderSubView(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        String heading = switch (subView) {
            case ADD_HERE -> "Add Waypoint Here";
            case ADD_MANUAL -> "Add Waypoint";
            case EDIT -> "Edit Waypoint";
        };
        int leftX = x + CONTENT_LEFT_PAD;
        guiGraphics.text(font, Component.literal(heading), leftX, y, COLOR_TITLE);

        int rowY = y + 20;
        guiGraphics.text(font, Component.literal("Name"), leftX, rowY + 4, COLOR_DESC);
        rowY += FORM_ROW_HEIGHT;

        if (subView != SubView.ADD_HERE) {
            guiGraphics.text(font, Component.literal("X"), leftX, rowY + 4, COLOR_DESC);
            guiGraphics.text(font, Component.literal("Y"), leftX + 90, rowY + 4, COLOR_DESC);
            guiGraphics.text(font, Component.literal("Z"), leftX + 180, rowY + 4, COLOR_DESC);
            rowY += FORM_ROW_HEIGHT;

            List<String> options = dimensionOptions();
            int pillX = leftX;
            for (String dimensionId : options) {
                String label = shortDimensionLabel(dimensionId);
                int pillWidth = font.width(label) + 16;
                boolean isActive = dimensionId.equals(formDimension);
                boolean hovered = mouseX >= pillX && mouseX <= pillX + pillWidth && mouseY >= rowY && mouseY <= rowY + PILL_HEIGHT;
                guiGraphics.fill(pillX, rowY, pillX + pillWidth, rowY + PILL_HEIGHT,
                        isActive ? COLOR_CONFIRM_BG : (hovered ? COLOR_ROW_HOVER : COLOR_ROW_IDLE));
                guiGraphics.centeredText(font, Component.literal(label), pillX + pillWidth / 2, rowY + 4, COLOR_TITLE);
                pillX += pillWidth + PILL_GAP;
            }
            rowY += PILL_HEIGHT + 6;
        }

        int buttonY = rowY + 6;
        int saveWidth = font.width("Save") + 20;
        boolean saveHovered = mouseX >= leftX && mouseX <= leftX + saveWidth && mouseY >= buttonY && mouseY <= buttonY + PILL_HEIGHT + 4;
        guiGraphics.fill(leftX, buttonY, leftX + saveWidth, buttonY + PILL_HEIGHT + 4, saveHovered ? COLOR_CONFIRM_BG : COLOR_BORDER);
        guiGraphics.centeredText(font, Component.literal("Save"), leftX + saveWidth / 2, buttonY + 6, COLOR_TITLE);

        int cancelX = leftX + saveWidth + PILL_GAP;
        int cancelWidth = font.width("Cancel") + 20;
        boolean cancelHovered = mouseX >= cancelX && mouseX <= cancelX + cancelWidth && mouseY >= buttonY && mouseY <= buttonY + PILL_HEIGHT + 4;
        guiGraphics.fill(cancelX, buttonY, cancelX + cancelWidth, buttonY + PILL_HEIGHT + 4, cancelHovered ? COLOR_ROW_HOVER : COLOR_BORDER);
        guiGraphics.centeredText(font, Component.literal("Cancel"), cancelX + cancelWidth / 2, buttonY + 6, COLOR_TITLE);
    }

    private static String shortDimensionLabel(String dimensionId) {
        int colon = dimensionId.indexOf(':');
        String path = colon >= 0 ? dimensionId.substring(colon + 1) : dimensionId;
        return switch (path) {
            case "overworld" -> "Overworld";
            case "the_nether" -> "Nether";
            case "the_end" -> "End";
            default -> path;
        };
    }

    // ---- Click handling ----------------------------------------------

    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        if (subView != null) {
            return subViewMouseClicked(x, y, width, height, mouseX, mouseY);
        }
        return listMouseClicked(x, y, width, height, mouseX, mouseY);
    }

    private boolean listMouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        Font font = Minecraft.getInstance().font;
        int leftX = x + CONTENT_LEFT_PAD;
        List<String> options = dimensionOptions();
        int pillY = y + 16;
        int pillX = leftX;
        for (String dimensionId : options) {
            String label = shortDimensionLabel(dimensionId);
            int pillWidth = font.width(label) + 16;
            if (mouseX >= pillX && mouseX <= pillX + pillWidth && mouseY >= pillY && mouseY <= pillY + PILL_HEIGHT) {
                MainMenuScreen.playClickSound();
                selectedDimension = dimensionId;
                armedDeleteId = null;
                return true;
            }
            pillX += pillWidth + PILL_GAP;
        }

        int addRowY = pillY + PILL_HEIGHT + 8;
        String active = activeDimension();
        boolean canAddHere = active != null && active.equals(scopeResolver.currentDimensionId());
        int addHereWidth = font.width("+ Add at current position") + 16;
        if (canAddHere && mouseX >= leftX && mouseX <= leftX + addHereWidth && mouseY >= addRowY && mouseY <= addRowY + PILL_HEIGHT) {
            MainMenuScreen.playClickSound();
            openAddHere(active, x, y);
            return true;
        }
        int addManualX = leftX + addHereWidth + PILL_GAP;
        int addManualWidth = font.width("+ Add manually") + 16;
        if (mouseX >= addManualX && mouseX <= addManualX + addManualWidth && mouseY >= addRowY && mouseY <= addRowY + PILL_HEIGHT) {
            MainMenuScreen.playClickSound();
            openAddManual(active, x, y);
            return true;
        }

        int contentY = addRowY + PILL_HEIGHT + 10;
        List<Waypoint> waypoints = active == null ? List.of() : registry.list(active);
        int rowY = contentY - scrollOffset;
        for (Waypoint waypoint : waypoints) {
            if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT) {
                return rowClicked(x, y, width, rowY, waypoint, font, mouseX, mouseY);
            }
            rowY += ROW_HEIGHT + 2;
        }
        armedDeleteId = null;
        return false;
    }

    private boolean rowClicked(int x, int y, int width, int rowY, Waypoint waypoint, Font font, double mouseX, double mouseY) {
        int rightEdge = x + width - CONTENT_LEFT_PAD;
        int controlY = rowY + (ROW_HEIGHT - PILL_HEIGHT) / 2;

        boolean armed = waypoint.id().equals(armedDeleteId);
        String deleteLabel = armed ? "Confirm?" : "Delete";
        int deleteWidth = font.width(deleteLabel) + 14;
        int deleteX = rightEdge - deleteWidth;
        if (mouseX >= deleteX && mouseX <= deleteX + deleteWidth && mouseY >= controlY && mouseY <= controlY + PILL_HEIGHT) {
            MainMenuScreen.playClickSound();
            if (armed) {
                registry.delete(waypoint.id());
                armedDeleteId = null;
            } else {
                armedDeleteId = waypoint.id();
            }
            return true;
        }

        String editLabel = "Edit";
        int editWidth = font.width(editLabel) + 14;
        int editX = deleteX - editWidth - PILL_GAP;
        if (mouseX >= editX && mouseX <= editX + editWidth && mouseY >= controlY && mouseY <= controlY + PILL_HEIGHT) {
            MainMenuScreen.playClickSound();
            openEdit(waypoint, x, y);
            return true;
        }

        armedDeleteId = null;
        return true;
    }

    private boolean subViewMouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        Font font = Minecraft.getInstance().font;
        int leftX = x + CONTENT_LEFT_PAD;
        int rowY = y + 20 + FORM_ROW_HEIGHT;

        if (subView != SubView.ADD_HERE) {
            rowY += FORM_ROW_HEIGHT;
            List<String> options = dimensionOptions();
            int pillX = leftX;
            for (String dimensionId : options) {
                String label = shortDimensionLabel(dimensionId);
                int pillWidth = font.width(label) + 16;
                if (mouseX >= pillX && mouseX <= pillX + pillWidth && mouseY >= rowY && mouseY <= rowY + PILL_HEIGHT) {
                    MainMenuScreen.playClickSound();
                    formDimension = dimensionId;
                    return true;
                }
                pillX += pillWidth + PILL_GAP;
            }
            rowY += PILL_HEIGHT + 6;
        }

        int buttonY = rowY + 6;
        int saveWidth = font.width("Save") + 20;
        if (mouseX >= leftX && mouseX <= leftX + saveWidth && mouseY >= buttonY && mouseY <= buttonY + PILL_HEIGHT + 4) {
            MainMenuScreen.playClickSound();
            commitSubView();
            return true;
        }
        int cancelX = leftX + saveWidth + PILL_GAP;
        int cancelWidth = font.width("Cancel") + 20;
        if (mouseX >= cancelX && mouseX <= cancelX + cancelWidth && mouseY >= buttonY && mouseY <= buttonY + PILL_HEIGHT + 4) {
            MainMenuScreen.playClickSound();
            closeSubView();
            return true;
        }
        return true; // swallow clicks inside the form area
    }

    public boolean mouseScrolled(int x, int y, int width, int height, double mouseX, double mouseY, double scrollDelta) {
        if (subView != null) {
            return false;
        }
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) {
            return false;
        }
        scrollOffset -= (int) Math.round(scrollDelta * SCROLL_STEP);
        if (scrollOffset < 0) {
            scrollOffset = 0;
        }
        return true;
    }

    // ---- Sub-view lifecycle -------------------------------------------

    private void openAddHere(String dimensionId, int x, int y) {
        Minecraft client = Minecraft.getInstance();
        BlockPos pos = client.player != null ? client.player.blockPosition() : BlockPos.ZERO;
        editingWaypointId = null;
        formDimension = dimensionId;
        ensureFormBoxes(x, y, SubView.ADD_HERE);
        nameBox.setValue("");
        xBox.setValue(String.valueOf(pos.getX()));
        yBox.setValue(String.valueOf(pos.getY()));
        zBox.setValue(String.valueOf(pos.getZ()));
        subView = SubView.ADD_HERE;
    }

    private void openAddManual(String dimensionId, int x, int y) {
        editingWaypointId = null;
        formDimension = dimensionId;
        ensureFormBoxes(x, y, SubView.ADD_MANUAL);
        nameBox.setValue("");
        xBox.setValue("0");
        yBox.setValue("64");
        zBox.setValue("0");
        subView = SubView.ADD_MANUAL;
    }

    private void openEdit(Waypoint waypoint, int x, int y) {
        editingWaypointId = waypoint.id();
        formDimension = waypoint.dimensionId();
        ensureFormBoxes(x, y, SubView.EDIT);
        nameBox.setValue(waypoint.name());
        xBox.setValue(String.valueOf(waypoint.x()));
        yBox.setValue(String.valueOf(waypoint.y()));
        zBox.setValue(String.valueOf(waypoint.z()));
        subView = SubView.EDIT;
    }

    /**
     * Positions the form's {@link EditBox} widgets next to the labels drawn by
     * {@link #renderSubView}: "Name" on the first form row, and (for ADD_MANUAL/EDIT,
     * which show a position editor) "X"/"Y"/"Z" on the row below, at the same
     * leftX/column offsets ({@code leftX}, {@code leftX + 90}, {@code leftX + 180})
     * used there.
     */
    private void ensureFormBoxes(int x, int y, SubView targetView) {
        removeFormBoxes();
        if (addWidget == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int leftX = x + CONTENT_LEFT_PAD;
        int nameRowY = y + 20;
        int xyzRowY = nameRowY + FORM_ROW_HEIGHT;
        nameBox = new EditBox(font, leftX + 40, nameRowY, 130, 14, Component.literal("Name"));
        // ADD_HERE derives X/Y/Z from the player's live position and never shows these
        // fields (see renderSubView's "subView != ADD_HERE" guard), but commitSubView()
        // still reads xBox/yBox/zBox, so they're always constructed -- just only added
        // to the screen (and thus made visible/interactive) when the form displays them.
        xBox = new EditBox(font, leftX + 14, xyzRowY, 70, 14, Component.literal("X"));
        yBox = new EditBox(font, leftX + 104, xyzRowY, 70, 14, Component.literal("Y"));
        zBox = new EditBox(font, leftX + 194, xyzRowY, 70, 14, Component.literal("Z"));

        addWidget.accept(nameBox);
        if (targetView != SubView.ADD_HERE) {
            addWidget.accept(xBox);
            addWidget.accept(yBox);
            addWidget.accept(zBox);
        }
    }

    private void removeFormBoxes() {
        if (removeWidget != null) {
            if (nameBox != null) {
                removeWidget.accept(nameBox);
            }
            if (xBox != null) {
                removeWidget.accept(xBox);
            }
            if (yBox != null) {
                removeWidget.accept(yBox);
            }
            if (zBox != null) {
                removeWidget.accept(zBox);
            }
        }
        nameBox = null;
        xBox = null;
        yBox = null;
        zBox = null;
    }

    private void commitSubView() {
        if (subView == null || nameBox == null) {
            closeSubView();
            return;
        }
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            name = "Waypoint";
        }
        int wx = parseIntOr(xBox, 0);
        int wy = parseIntOr(yBox, 64);
        int wz = parseIntOr(zBox, 0);
        String dimensionId = formDimension != null ? formDimension : scopeResolver.currentDimensionId();

        switch (subView) {
            case ADD_HERE, ADD_MANUAL -> {
                if (dimensionId != null) {
                    registry.add(name, wx, wy, wz, dimensionId);
                }
            }
            case EDIT -> {
                if (editingWaypointId != null && dimensionId != null) {
                    registry.rename(editingWaypointId, name);
                    registry.editPosition(editingWaypointId, wx, wy, wz, dimensionId);
                }
            }
        }
        closeSubView();
    }

    private static int parseIntOr(EditBox box, int fallback) {
        if (box == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void closeSubView() {
        removeFormBoxes();
        subView = null;
        editingWaypointId = null;
        formDimension = null;
    }
}
