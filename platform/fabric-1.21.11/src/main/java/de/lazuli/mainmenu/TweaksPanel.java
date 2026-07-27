package de.lazuli.mainmenu;

import de.lazuli.api.tweaks.TweakDefinition;
import de.lazuli.api.tweaks.TweakId;
import de.lazuli.api.tweaks.TweakState;
import de.lazuli.features.tweaks.services.ConfigFieldSpec;
import de.lazuli.features.tweaks.services.ConfigSchemas;
import de.lazuli.tweaks.TweaksBundle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Tweaks tab panel (spec F2) -- Yarn-mapped port of
 * {@code fabric-26.1}/{@code fabric-26.2}'s class of the same name. See that
 * class's Javadoc for the config-screen mechanism this implements.
 *
 * <p><strong>Visual redesign (tweaks-panel-visual-redesign):</strong> row
 * list and config-screen rows both follow {@link AchievementsPanel}'s row
 * idiom -- {@link #COLOR_ROW_IDLE}/{@link #COLOR_ROW_HOVER} background fill,
 * title + dimmer description line, right-aligned interactive control -- and
 * the config-screen heading is rendered at {@link #HEADING_SCALE} via the
 * same {@code getMatrices().pushMatrix()/scale()/popMatrix()} idiom
 * {@code HomePanel}'s greeting line established. Purely visual: the
 * {@code configuring} panel-swap mechanism, back-button navigation, and the
 * {@link ConfigFieldSpec}/{@link ConfigSchemas} data model are unchanged.
 */
public final class TweaksPanel {

    private static final int ROW_HEIGHT = 24;
    private static final int ROW_GAP = 2;
    private static final int CONFIG_ROW_HEIGHT = 24;
    private static final int CONFIG_ROW_GAP = 2;
    private static final int LIST_ENTRY_HEIGHT = 18;
    private static final int CONTENT_LEFT_PAD = 8;
    private static final int SCROLL_STEP = 16;
    private static final int CHECKBOX_SIZE = 12;
    private static final int PILL_HEIGHT = 14;
    private static final float HEADING_SCALE = 1.6f;

    private static final int COLOR_ROW_IDLE = 0xFF201E17;
    private static final int COLOR_ROW_HOVER = 0xFF2A2820;
    private static final int COLOR_TITLE = 0xFFEAE8E1;
    private static final int COLOR_DESC = 0xFF908C7F;
    private static final int COLOR_ACCENT = 0xFFC9A227;
    private static final int COLOR_BORDER = 0xFF141210;
    private static final int COLOR_ARMED_BG = 0xFF3A3020;
    private static final int COLOR_CHECK_ON = 0xFF528A54;

    private final TweaksBundle bundle;
    private int scrollOffset;

    private TweakId armedBindTarget;
    private boolean armedIsSecondary;

    /** Non-null while the config screen for that tweak is showing (row list otherwise). */
    private TweakId configuring;

    private Consumer<ClickableWidget> addWidget;
    private Consumer<ClickableWidget> removeWidget;
    private ButtonWidget backButton;

    /** Non-null while a string-list "+ Add" text field is live, naming the key it's adding to. */
    private String addingToKey;
    private TextFieldWidget addEditBox;

    public TweaksPanel(TweaksBundle bundle) {
        this.bundle = bundle;
    }

    public void init(Consumer<ClickableWidget> addWidget, Consumer<ClickableWidget> removeWidget, int x, int y, int width) {
        this.addWidget = addWidget;
        this.removeWidget = removeWidget;
    }

    private record Layout(TweakId id, int y) {
    }

    private List<Layout> layout(int x, int y, int width) {
        List<Layout> rows = new ArrayList<>();
        int rowY = y - scrollOffset;
        for (TweakDefinition def : bundle.registry().all()) {
            rows.add(new Layout(def.id(), rowY));
            rowY += ROW_HEIGHT + ROW_GAP;
        }
        return rows;
    }

    public void render(DrawContext context, TextRenderer font, int x, int y, int width, int height, int mouseX, int mouseY) {
        if (configuring != null) {
            renderConfigScreen(context, font, x, y, width, height, mouseX, mouseY);
            return;
        }
        context.drawText(font, Text.literal("Tweaks"), x + CONTENT_LEFT_PAD, y, COLOR_TITLE, false);
        int contentY = y + 20;
        int contentHeight = height - 20;
        context.enableScissor(x, contentY, x + width, contentY + contentHeight);
        for (Layout row : layout(x, contentY, width)) {
            renderRow(context, font, x, width, row, mouseX, mouseY);
        }
        context.disableScissor();
        if (armedBindTarget != null) {
            context.drawText(font, Text.literal("Press a key to bind... (Esc to cancel)"),
                    x + CONTENT_LEFT_PAD, y + height - 12, COLOR_ACCENT, false);
        }
    }

    private void renderRow(DrawContext context, TextRenderer font, int x, int width, Layout row, int mouseX, int mouseY) {
        TweakDefinition def = defOf(row.id());
        TweakState state = bundle.registry().stateOf(row.id());
        int rowY = row.y();

        boolean rowHovered = mouseY >= rowY && mouseY < rowY + ROW_HEIGHT && mouseX >= x && mouseX <= x + width;
        context.fill(x, rowY, x + width, rowY + ROW_HEIGHT, rowHovered ? COLOR_ROW_HOVER : COLOR_ROW_IDLE);

        int cbX = x + CONTENT_LEFT_PAD;
        int cbY = rowY + (ROW_HEIGHT - CHECKBOX_SIZE) / 2;

        if (isComingSoon(row.id())) {
            // T10 Disable Cosmetics: no in-world cosmetics renderer exists yet
            // (see docs/specs/tweaks-hooks-wiring.md T10 section) -- render a
            // non-interactive, dimmed row with hint text instead of a normal
            // enable checkbox.
            context.fill(cbX, cbY, cbX + CHECKBOX_SIZE, cbY + CHECKBOX_SIZE, COLOR_BORDER);
            int textX = cbX + CHECKBOX_SIZE + 6;
            context.drawText(font, Text.literal(displayName(row.id())), textX, rowY + 4, COLOR_DESC, false);
            context.drawText(font, Text.literal(COMING_SOON_HINT), textX, rowY + 14, COLOR_DESC, false);
            return;
        }

        drawCheckbox(context, cbX, cbY, state.enabled());

        int textX = cbX + CHECKBOX_SIZE + 6;
        context.drawText(font, Text.literal(displayName(row.id())), textX, rowY + 4, COLOR_TITLE, false);
        context.drawText(font, Text.literal(def.description()), textX, rowY + 14, COLOR_DESC, false);

        renderBindArea(context, font, row.id(), x + width - CONTENT_LEFT_PAD, rowY, ROW_HEIGHT, mouseX, mouseY);
    }

    /** T10 Disable Cosmetics: coming-soon hint shown in place of its config UI (spec R8). */
    private static final String COMING_SOON_HINT =
            "Coming soon - requires an in-world cosmetics renderer that doesn't exist yet.";

    private static boolean isComingSoon(TweakId id) {
        return id == TweakId.DISABLE_COSMETICS;
    }

    /**
     * Shared right-column hotkey control renderer used by both the row-list
     * rows and the config screen's first "Hotkey" row: a bordered/hoverable
     * pill showing the primary bind, plus (only for
     * {@link TweakDefinition#hasSecondaryKeyBinding()}) a dimmer secondary
     * bind label under it.
     */
    private void renderBindArea(DrawContext context, TextRenderer font, TweakId id, int rightEdge, int rowY, int rowHeight, int mouseX, int mouseY) {
        TweakDefinition def = defOf(id);
        boolean hasSecondary = def.hasSecondaryKeyBinding();
        KeyBinding binding = bundle.keyBindings().keyBindingOf(id);
        boolean primaryArmed = armedBindTarget == id && !armedIsSecondary;
        String bindLabel = primaryArmed ? "..." : binding.getBoundKeyLocalizedText().getString();
        int pillWidth = font.getWidth(bindLabel) + 16;
        int pillX = rightEdge - pillWidth;
        int pillY = hasSecondary ? rowY + 2 : rowY + (rowHeight - PILL_HEIGHT) / 2;
        boolean pillHovered = mouseX >= pillX && mouseX <= pillX + pillWidth && mouseY >= pillY && mouseY <= pillY + PILL_HEIGHT;
        int pillColor = primaryArmed ? COLOR_ARMED_BG : (pillHovered ? COLOR_ROW_HOVER : COLOR_BORDER);
        context.fill(pillX, pillY, pillX + pillWidth, pillY + PILL_HEIGHT, pillColor);
        context.drawCenteredTextWithShadow(font, bindLabel, pillX + pillWidth / 2, pillY + 3, COLOR_ACCENT);

        if (hasSecondary) {
            KeyBinding secondary = bundle.keyBindings().secondaryKeyBindingOf(id);
            boolean secondaryArmed = armedBindTarget == id && armedIsSecondary;
            String secLabel = "Whitelist: " + (secondaryArmed ? "..." : secondary.getBoundKeyLocalizedText().getString());
            int secWidth = font.getWidth(secLabel);
            context.drawText(font, Text.literal(secLabel), rightEdge - secWidth, pillY + PILL_HEIGHT + 3,
                    secondaryArmed ? COLOR_ACCENT : COLOR_DESC, false);
        }
    }

    /** Bordered checkbox: filled square when {@code value}, empty otherwise. */
    private static void drawCheckbox(DrawContext context, int x, int y, boolean value) {
        context.fill(x, y, x + CHECKBOX_SIZE, y + CHECKBOX_SIZE, COLOR_BORDER);
        if (value) {
            context.fill(x + 2, y + 2, x + CHECKBOX_SIZE - 2, y + CHECKBOX_SIZE - 2, COLOR_CHECK_ON);
        }
    }

    private static TweakDefinition defOf(TweakId id) {
        for (TweakDefinition def : de.lazuli.features.tweaks.services.TweakDefinitions.ALL) {
            if (def.id() == id) {
                return def;
            }
        }
        throw new IllegalStateException();
    }

    private static String displayName(TweakId id) {
        String[] parts = id.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    // ---- Config screen -------------------------------------------------

    private record ConfigRow(ConfigFieldSpec spec, int y, int height) {
    }

    private List<ConfigRow> configLayout(TweakId id, int contentY) {
        List<ConfigRow> rows = new ArrayList<>();
        TweakState state = bundle.registry().stateOf(id);
        int rowY = contentY;
        for (ConfigFieldSpec spec : ConfigSchemas.fieldsFor(id)) {
            int rowHeight;
            if (spec.kind() == ConfigFieldSpec.Kind.STRING_LIST) {
                List<?> list = asList(state.configurable(spec.key()));
                rowHeight = CONFIG_ROW_HEIGHT + (list.size() + 1) * LIST_ENTRY_HEIGHT;
            } else {
                rowHeight = CONFIG_ROW_HEIGHT;
            }
            rows.add(new ConfigRow(spec, rowY, rowHeight));
            rowY += rowHeight + CONFIG_ROW_GAP;
        }
        return rows;
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    /** Height of the synthetic hotkey row rendered first in the config screen. */
    private int hotkeyRowHeight(TweakId id) {
        return defOf(id).hasSecondaryKeyBinding() ? 32 : 24;
    }

    private void renderConfigScreen(DrawContext context, TextRenderer font, int x, int y, int width, int height, int mouseX, int mouseY) {
        TweakId id = configuring;
        ensureBackButton(x, y);

        int headingX = x + CONTENT_LEFT_PAD + 68;
        int headingY = y + 2;
        context.getMatrices().pushMatrix();
        context.getMatrices().scale(HEADING_SCALE, HEADING_SCALE);
        context.drawText(font, Text.literal(displayName(id)),
                (int) (headingX / HEADING_SCALE), (int) (headingY / HEADING_SCALE), COLOR_TITLE, false);
        context.getMatrices().popMatrix();

        int contentY = y + 30;
        int contentHeight = height - 30;
        context.enableScissor(x, contentY, x + width, contentY + contentHeight);

        int hotkeyHeight = hotkeyRowHeight(id);
        renderHotkeyRow(context, font, x, width, contentY, hotkeyHeight, id, mouseX, mouseY);

        int fieldsY = contentY + hotkeyHeight + CONFIG_ROW_GAP;
        TweakState state = bundle.registry().stateOf(id);
        for (ConfigRow row : configLayout(id, fieldsY)) {
            renderConfigRow(context, font, x, width, row, state, mouseX, mouseY);
        }
        context.disableScissor();
    }

    private void renderHotkeyRow(DrawContext context, TextRenderer font, int x, int width, int rowY, int rowHeight, TweakId id, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY < rowY + rowHeight;
        context.fill(x, rowY, x + width, rowY + rowHeight, hovered ? COLOR_ROW_HOVER : COLOR_ROW_IDLE);
        context.drawText(font, Text.literal("Hotkey"), x + CONTENT_LEFT_PAD, rowY + 5, COLOR_TITLE, false);
        renderBindArea(context, font, id, x + width - CONTENT_LEFT_PAD, rowY, rowHeight, mouseX, mouseY);
    }

    private void ensureBackButton(int x, int y) {
        if (backButton == null && addWidget != null) {
            backButton = ButtonWidget.builder(Text.literal("< Back"), b -> {
                        MainMenuScreen.playClickSound();
                        leaveConfigScreen();
                    })
                    .dimensions(x + CONTENT_LEFT_PAD, y, 60, 16).build();
            addWidget.accept(backButton);
        }
    }

    /**
     * Returns to the row-list view, tearing down the back button and any
     * live string-list "+ Add" {@link TextFieldWidget}. Also called
     * externally by {@code MainMenuScreen} when switching tabs away from
     * Tweaks or on screen close (plan §7 Risk #1: avoid leaking a stale
     * widget).
     */
    public void leaveConfigScreen() {
        if (configuring == null) {
            return;
        }
        cancelStringListEdit();
        if (backButton != null && removeWidget != null) {
            removeWidget.accept(backButton);
        }
        backButton = null;
        configuring = null;
    }

    private void renderConfigRow(DrawContext context, TextRenderer font, int x, int width, ConfigRow row, TweakState state, int mouseX, int mouseY) {
        ConfigFieldSpec spec = row.spec();
        Object current = state.configurable(spec.key());
        int rowY = row.y();
        int rightEdge = x + width - CONTENT_LEFT_PAD;

        switch (spec.kind()) {
            case BOOLEAN -> {
                boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY < rowY + row.height();
                context.fill(x, rowY, x + width, rowY + row.height(), hovered ? COLOR_ROW_HOVER : COLOR_ROW_IDLE);
                context.drawText(font, Text.literal(spec.label()), x + CONTENT_LEFT_PAD, rowY + (CONFIG_ROW_HEIGHT - 8) / 2, COLOR_TITLE, false);
                boolean value = current instanceof Boolean b && b;
                drawCheckbox(context, rightEdge - CHECKBOX_SIZE, rowY + (CONFIG_ROW_HEIGHT - CHECKBOX_SIZE) / 2, value);
            }
            case NUMERIC -> {
                boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY < rowY + row.height();
                context.fill(x, rowY, x + width, rowY + row.height(), hovered ? COLOR_ROW_HOVER : COLOR_ROW_IDLE);
                context.drawText(font, Text.literal(spec.label()), x + CONTENT_LEFT_PAD, rowY + (CONFIG_ROW_HEIGHT - 8) / 2, COLOR_TITLE, false);
                renderNumericStepper(context, font, spec, current, rightEdge, rowY, mouseX, mouseY);
            }
            case ENUM -> {
                boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY < rowY + row.height();
                context.fill(x, rowY, x + width, rowY + row.height(), hovered ? COLOR_ROW_HOVER : COLOR_ROW_IDLE);
                context.drawText(font, Text.literal(spec.label()), x + CONTENT_LEFT_PAD, rowY + (CONFIG_ROW_HEIGHT - 8) / 2, COLOR_TITLE, false);
                renderEnumPill(context, font, current, rightEdge, rowY, mouseX, mouseY);
            }
            case STRING_LIST -> {
                context.drawText(font, Text.literal(spec.label()), x + CONTENT_LEFT_PAD, rowY + (CONFIG_ROW_HEIGHT - 8) / 2, COLOR_TITLE, false);
                List<?> list = asList(current);
                int entryY = rowY + CONFIG_ROW_HEIGHT;
                for (Object entry : list) {
                    boolean entryHovered = mouseX >= x && mouseX <= x + width && mouseY >= entryY && mouseY < entryY + LIST_ENTRY_HEIGHT;
                    context.fill(x + CONTENT_LEFT_PAD, entryY, rightEdge, entryY + LIST_ENTRY_HEIGHT - 2, entryHovered ? COLOR_ROW_HOVER : COLOR_BORDER);
                    context.drawText(font, Text.literal(String.valueOf(entry)), x + CONTENT_LEFT_PAD + 6, entryY + 5, COLOR_TITLE, false);
                    int removeSize = 12;
                    int removeX = rightEdge - removeSize - 4;
                    int removeY = entryY + (LIST_ENTRY_HEIGHT - 2 - removeSize) / 2;
                    context.fill(removeX, removeY, removeX + removeSize, removeY + removeSize, 0xFF6B2A2A);
                    context.drawCenteredTextWithShadow(font, "x", removeX + removeSize / 2, removeY + 2, COLOR_TITLE);
                    entryY += LIST_ENTRY_HEIGHT;
                }
                boolean addHovered = mouseX >= x && mouseX <= x + width && mouseY >= entryY && mouseY < entryY + LIST_ENTRY_HEIGHT;
                context.fill(x + CONTENT_LEFT_PAD, entryY, rightEdge, entryY + LIST_ENTRY_HEIGHT - 2, addHovered ? COLOR_ROW_HOVER : COLOR_ROW_IDLE);
                String addLabel = spec.key().equals(addingToKey) ? "(typing...)" : "+ Add";
                context.drawText(font, Text.literal(addLabel), x + CONTENT_LEFT_PAD + 6, entryY + 5, COLOR_DESC, false);
            }
        }
    }

    private void renderNumericStepper(DrawContext context, TextRenderer font, ConfigFieldSpec spec, Object current, int rightEdge, int rowY, int mouseX, int mouseY) {
        String valueStr = formatNumeric(current);
        int valueWidth = Math.max(font.getWidth(valueStr) + 10, 28);
        int stepBox = 16;
        int totalWidth = stepBox + valueWidth + stepBox + 4;
        int startX = rightEdge - totalWidth;
        int boxY = rowY + (CONFIG_ROW_HEIGHT - PILL_HEIGHT) / 2;

        int minusX = startX;
        boolean minusHovered = mouseX >= minusX && mouseX <= minusX + stepBox && mouseY >= boxY && mouseY <= boxY + PILL_HEIGHT;
        context.fill(minusX, boxY, minusX + stepBox, boxY + PILL_HEIGHT, minusHovered ? COLOR_ROW_HOVER : COLOR_BORDER);
        context.drawCenteredTextWithShadow(font, "-", minusX + stepBox / 2, boxY + 3, COLOR_ACCENT);

        int valueX = minusX + stepBox + 2;
        context.fill(valueX, boxY, valueX + valueWidth, boxY + PILL_HEIGHT, COLOR_BORDER);
        context.drawCenteredTextWithShadow(font, valueStr, valueX + valueWidth / 2, boxY + 3, COLOR_TITLE);

        int plusX = valueX + valueWidth + 2;
        boolean plusHovered = mouseX >= plusX && mouseX <= plusX + stepBox && mouseY >= boxY && mouseY <= boxY + PILL_HEIGHT;
        context.fill(plusX, boxY, plusX + stepBox, boxY + PILL_HEIGHT, plusHovered ? COLOR_ROW_HOVER : COLOR_BORDER);
        context.drawCenteredTextWithShadow(font, "+", plusX + stepBox / 2, boxY + 3, COLOR_ACCENT);
    }

    private void renderEnumPill(DrawContext context, TextRenderer font, Object current, int rightEdge, int rowY, int mouseX, int mouseY) {
        String label = String.valueOf(current);
        int pillWidth = font.getWidth(label) + 16;
        int pillX = rightEdge - pillWidth;
        int pillY = rowY + (CONFIG_ROW_HEIGHT - PILL_HEIGHT) / 2;
        boolean hovered = mouseX >= pillX && mouseX <= pillX + pillWidth && mouseY >= pillY && mouseY <= pillY + PILL_HEIGHT;
        context.fill(pillX, pillY, pillX + pillWidth, pillY + PILL_HEIGHT, hovered ? COLOR_ROW_HOVER : COLOR_BORDER);
        context.drawCenteredTextWithShadow(font, label, pillX + pillWidth / 2, pillY + 3, COLOR_ACCENT);
    }

    private static String formatNumeric(Object value) {
        if (value instanceof Double d) {
            return String.format(Locale.ROOT, "%.2f", d);
        }
        return String.valueOf(value);
    }

    // ---- Click handling --------------------------------------------------

    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        if (configuring != null) {
            return configScreenMouseClicked(x, y, width, height, mouseX, mouseY);
        }
        int contentY = y + 20;
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        for (Layout row : layout(x, contentY, width)) {
            if (mouseY < row.y() || mouseY >= row.y() + ROW_HEIGHT) {
                continue;
            }
            if (isComingSoon(row.id())) {
                // Non-interactive per T10's UI-only scope: consume the click,
                // do not toggle/open a config screen.
                return true;
            }

            TweakDefinition def = defOf(row.id());
            int rowY = row.y();

            int cbX = x + CONTENT_LEFT_PAD;
            if (mouseX >= cbX && mouseX <= cbX + CHECKBOX_SIZE + 4) {
                MainMenuScreen.playClickSound();
                bundle.registry().setEnabled(row.id(), !bundle.registry().stateOf(row.id()).enabled());
                return true;
            }

            if (handleBindAreaClick(row.id(), def, x + width - CONTENT_LEFT_PAD, rowY, ROW_HEIGHT, font, mouseX, mouseY)) {
                return true;
            }

            // Any remaining click on the row body opens the config screen.
            MainMenuScreen.playClickSound();
            configuring = row.id();
            return true;
        }
        return false;
    }

    /** @return true if the click landed on the primary or secondary bind control and was handled. */
    private boolean handleBindAreaClick(TweakId id, TweakDefinition def, int rightEdge, int rowY, int rowHeight, TextRenderer font, double mouseX, double mouseY) {
        boolean hasSecondary = def.hasSecondaryKeyBinding();
        KeyBinding binding = bundle.keyBindings().keyBindingOf(id);
        String bindLabel = binding.getBoundKeyLocalizedText().getString();
        int pillWidth = font.getWidth(bindLabel) + 16;
        int pillX = rightEdge - pillWidth;
        int pillY = hasSecondary ? rowY + 2 : rowY + (rowHeight - PILL_HEIGHT) / 2;
        if (mouseX >= pillX && mouseX <= pillX + pillWidth && mouseY >= pillY && mouseY <= pillY + PILL_HEIGHT) {
            MainMenuScreen.playClickSound();
            armedBindTarget = id;
            armedIsSecondary = false;
            return true;
        }
        if (hasSecondary) {
            KeyBinding secondary = bundle.keyBindings().secondaryKeyBindingOf(id);
            String secLabel = "Whitelist: " + secondary.getBoundKeyLocalizedText().getString();
            int secWidth = font.getWidth(secLabel);
            int secX = rightEdge - secWidth;
            int secY = pillY + PILL_HEIGHT + 3;
            if (mouseX >= secX && mouseX <= rightEdge && mouseY >= secY && mouseY <= secY + 9) {
                MainMenuScreen.playClickSound();
                armedBindTarget = id;
                armedIsSecondary = true;
                return true;
            }
        }
        return false;
    }

    private boolean configScreenMouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        TweakId id = configuring;
        int contentY = y + 30;
        int hotkeyHeight = hotkeyRowHeight(id);
        if (mouseY >= contentY && mouseY < contentY + hotkeyHeight) {
            TextRenderer font = MinecraftClient.getInstance().textRenderer;
            handleBindAreaClick(id, defOf(id), x + width - CONTENT_LEFT_PAD, contentY, hotkeyHeight, font, mouseX, mouseY);
            return true;
        }
        int fieldsY = contentY + hotkeyHeight + CONFIG_ROW_GAP;
        TweakState state = bundle.registry().stateOf(id);
        for (ConfigRow row : configLayout(id, fieldsY)) {
            if (mouseY < row.y() || mouseY >= row.y() + row.height()) {
                continue;
            }
            ConfigFieldSpec spec = row.spec();
            Object current = state.configurable(spec.key());
            switch (spec.kind()) {
                case BOOLEAN -> {
                    MainMenuScreen.playClickSound();
                    boolean value = current instanceof Boolean b && b;
                    bundle.registry().setConfigurable(id, spec.key(), !value);
                    return true;
                }
                case NUMERIC -> {
                    return handleNumericClick(id, spec, current, x, width, row, mouseX);
                }
                case ENUM -> {
                    MainMenuScreen.playClickSound();
                    int index = spec.enumValues().indexOf(String.valueOf(current));
                    int nextIndex = (index + 1) % spec.enumValues().size();
                    bundle.registry().setConfigurable(id, spec.key(), spec.enumValues().get(nextIndex));
                    return true;
                }
                case STRING_LIST -> {
                    return handleStringListClick(id, spec, current, x, width, row, mouseY);
                }
            }
        }
        return true; // swallow clicks inside the config-screen content area
    }

    private boolean handleNumericClick(TweakId id, ConfigFieldSpec spec, Object current, int x, int width, ConfigRow row, double mouseX) {
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        String valueStr = formatNumeric(current);
        int valueWidth = Math.max(font.getWidth(valueStr) + 10, 28);
        int stepBox = 16;
        int totalWidth = stepBox + valueWidth + stepBox + 4;
        int rightEdge = x + width - CONTENT_LEFT_PAD;
        int startX = rightEdge - totalWidth;
        int minusX = startX;
        int plusX = startX + stepBox + 2 + valueWidth + 2;
        if (mouseX < minusX || mouseX > plusX + stepBox) {
            return true;
        }
        boolean increase = mouseX >= plusX;
        boolean decrease = mouseX <= minusX + stepBox;
        if (!increase && !decrease) {
            return true;
        }
        MainMenuScreen.playClickSound();
        double step = increase ? spec.numericStep() : -spec.numericStep();
        Object next = switch (current) {
            case Double d -> clamp(d + step, spec.numericMin(), spec.numericMax());
            case Integer i -> (int) Math.round(clamp(i + step, spec.numericMin(), spec.numericMax()));
            default -> current;
        };
        bundle.registry().setConfigurable(id, spec.key(), next);
        return true;
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(min) || Double.isNaN(max)) {
            return value;
        }
        return Math.max(min, Math.min(max, value));
    }

    private boolean handleStringListClick(TweakId id, ConfigFieldSpec spec, Object current, int x, int width, ConfigRow row, double mouseY) {
        List<?> list = asList(current);
        int entryY = row.y() + CONFIG_ROW_HEIGHT;
        for (int i = 0; i < list.size(); i++) {
            if (mouseY >= entryY && mouseY < entryY + LIST_ENTRY_HEIGHT) {
                MainMenuScreen.playClickSound();
                List<Object> updated = new ArrayList<>(list);
                updated.remove(i);
                bundle.registry().setConfigurable(id, spec.key(), updated);
                return true;
            }
            entryY += LIST_ENTRY_HEIGHT;
        }
        if (mouseY >= entryY && mouseY < entryY + LIST_ENTRY_HEIGHT) {
            MainMenuScreen.playClickSound();
            beginStringListAdd(spec.key(), x, width, entryY);
            return true;
        }
        return true;
    }

    private void beginStringListAdd(String key, int x, int width, int rowY) {
        cancelStringListEdit();
        if (addWidget == null) {
            return;
        }
        addingToKey = key;
        int boxWidth = width - CONTENT_LEFT_PAD * 2 - 20;
        addEditBox = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, x + CONTENT_LEFT_PAD + 6, rowY, boxWidth, 14, Text.literal("Add"));
        addWidget.accept(addEditBox);
    }

    private void commitStringListAdd() {
        if (addingToKey == null || addEditBox == null) {
            return;
        }
        String text = addEditBox.getText().trim();
        if (!text.isEmpty()) {
            List<?> current = asList(bundle.registry().stateOf(configuring).configurable(addingToKey));
            List<Object> updated = new ArrayList<>(current);
            updated.add(text);
            bundle.registry().setConfigurable(configuring, addingToKey, updated);
        }
        removeAddEditBox();
    }

    private void cancelStringListEdit() {
        removeAddEditBox();
    }

    private void removeAddEditBox() {
        if (addEditBox != null && removeWidget != null) {
            removeWidget.accept(addEditBox);
        }
        addEditBox = null;
        addingToKey = null;
    }

    /**
     * Called from {@code MainMenuScreen.keyPressed} while a bind is armed
     * (Architecture Decision 4): captures the next key press as this
     * tweak's new binding, Escape cancels. Also handles Enter/Escape for a
     * live string-list "+ Add" {@link TextFieldWidget}, checked first so it
     * never collides with bind-capture.
     */
    public boolean keyPressed(KeyInput input) {
        if (addingToKey != null) {
            if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                MainMenuScreen.playClickSound();
                commitStringListAdd();
                return true;
            }
            if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                cancelStringListEdit();
                return true;
            }
            return false;
        }
        if (armedBindTarget == null) {
            return false;
        }
        if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            armedBindTarget = null;
            return true;
        }
        KeyBinding binding = armedIsSecondary
                ? bundle.keyBindings().secondaryKeyBindingOf(armedBindTarget)
                : bundle.keyBindings().keyBindingOf(armedBindTarget);
        binding.setBoundKey(InputUtil.fromKeyCode(input));
        KeyBinding.updateKeysByCode();
        armedBindTarget = null;
        return true;
    }

    public boolean isArmedForBind() {
        return armedBindTarget != null || addingToKey != null;
    }

    public boolean mouseScrolled(int x, int y, int width, int height, double mouseX, double mouseY, double scrollDelta) {
        if (configuring != null) {
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
}
