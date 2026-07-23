package de.lazuli.mainmenu;

import de.lazuli.LazuliMod;
import de.lazuli.features.mainmenu.services.MainMenuStateMachine;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.level.storage.LevelSummary;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Worlds tab panel (specification FR3) -- {@code fabric-1.21.11} (Yarn-mapped,
 * obfuscated) port of the {@code fabric-26.1}/{@code fabric-26.2} class of the
 * same name.
 *
 * <p><strong>Real vanilla data/actions, no reimplementation</strong>: rather
 * than reconstructing vanilla's private "load a world"/"open the edit flow"
 * logic by hand, this panel builds one headless (never added to the widget
 * tree, never rendered by vanilla) {@link WorldListWidget} via its own public
 * {@link WorldListWidget.Builder}, letting it {@link WorldListWidget#load()}
 * the real save list and expose each row as a real
 * {@link WorldListWidget.WorldEntry} -- whose {@code play()}/{@code edit()}
 * instance methods (both public, confirmed via {@code javap}) this panel
 * calls directly for FR3.6/FR3.7, so the actual world-load/edit flow is
 * vanilla's own, never re-implemented here. Row rendering itself is still
 * this panel's own layout (Overview/UI), using only the entries' public
 * {@code getLevel()}/{@code getLevelDisplayName()} accessors as data.
 */
public final class WorldsPanel {

    private static final int ROW_HEIGHT_COMPACT = 32;
    private static final int ROW_HEIGHT_EXPANDED = 72;
    private static final int ICON_TEX_SIZE = 64;
    private static final int IMAGE_MARGIN = 2;
    private static final DateTimeFormatter LAST_PLAYED_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final MainMenuStateMachine state;
    private final MainMenuScreen owner;
    private final IconTextureCache iconCache = new IconTextureCache(LazuliMod.LOGGER::warn);
    private WorldListWidget dataWidget;
    private List<WorldListWidget.WorldEntry> entries = List.of();
    private ButtonWidget createButton;
    private boolean tabActive;

    public WorldsPanel(MainMenuStateMachine state, MainMenuScreen owner) {
        this.state = state;
        this.owner = owner;
        reload();
    }

    private void reload() {
        iconCache.invalidateAll();
        try {
            dataWidget = new WorldListWidget.Builder(MinecraftClient.getInstance(), owner)
                    .width(320).height(240)
                    .toWidget();
            dataWidget.load();
            List<WorldListWidget.WorldEntry> loaded = new ArrayList<>();
            for (WorldListWidget.Entry entry : dataWidget.children()) {
                if (entry instanceof WorldListWidget.WorldEntry worldEntry) {
                    loaded.add(worldEntry);
                }
            }
            this.entries = loaded;
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to load saved world list: " + e);
            this.entries = List.of();
        }
    }

    /** Called once, when the tab bar/screen constructs the panel's own buttons. */
    public void init(java.util.function.Consumer<ButtonWidget> addWidget, int x, int y, int width) {
        createButton = ButtonWidget.builder(Text.literal("+ Create New World"),
                        b -> CreateWorldScreen.show(MinecraftClient.getInstance(), () -> MinecraftClient.getInstance().setScreen(owner)))
                .dimensions(x + width - 160, y, 160, 20).build();
        createButton.visible = tabActive;
        addWidget.accept(createButton);
    }

    /** FX3.1: "+ Create New World" is only visible while the Worlds tab is the active one. */
    public void setTabActive(boolean active) {
        this.tabActive = active;
        if (createButton != null) {
            createButton.visible = active;
        }
    }

    public void render(DrawContext context, TextRenderer font, int x, int y, int width, int height, int mouseX, int mouseY) {
        context.drawText(font, Text.literal("Singleplayer Worlds"), x, y + 6, 0xFFEAE8E1, false);

        int rowY = y + 30;
        if (entries.isEmpty()) {
            context.drawText(font, Text.literal("No saved worlds yet."), x, rowY, 0xFF908C7F, false);
            return;
        }

        for (WorldListWidget.WorldEntry entry : entries) {
            LevelSummary summary = entry.getLevel();
            boolean expanded = summary.getName().equals(state.expandedRowId());
            int rowHeight = expanded ? ROW_HEIGHT_EXPANDED : ROW_HEIGHT_COMPACT;
            if (rowY + rowHeight > y + height) {
                break;
            }
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + rowHeight;
            context.fill(x, rowY, x + width, rowY + rowHeight, hovered ? 0xFF2A2820 : 0xFF201E17);

            // FX13.1/FX13.2: real-or-fallback world icon thumbnail, sized to
            // 2/3 of the row height (full-height read as oversized against
            // the text), 1:1, no border, vertically centered.
            int iconSize = (rowHeight - IMAGE_MARGIN * 2) * 2 / 3;
            int iconX = x + IMAGE_MARGIN;
            int iconY = rowY + (rowHeight - iconSize) / 2;
            Identifier iconId = iconCache.forWorld(summary.getName(), summary.getIconPath());
            context.drawTexture(RenderPipelines.GUI_TEXTURED, iconId, iconX, iconY, 0f, 0f,
                    iconSize, iconSize, ICON_TEX_SIZE, ICON_TEX_SIZE);

            int textX = iconX + iconSize + 6;
            context.drawText(font, Text.literal(entry.getLevelDisplayName()), textX, rowY + 4, 0xFFEAE8E1, false);
            String subtitle = summary.getGameMode().getTranslatableName().getString() + " · "
                    + LAST_PLAYED_FORMAT.format(Instant.ofEpochMilli(summary.getLastPlayed()));
            context.drawText(font, Text.literal(subtitle), textX, rowY + 15, 0xFF908C7F, false);

            if (expanded) {
                int buttonY = rowY + rowHeight - 22;
                boolean playHover = mouseX >= x + width - 140 && mouseX <= x + width - 74 && mouseY >= buttonY && mouseY <= buttonY + 18;
                boolean editHover = mouseX >= x + width - 70 && mouseX <= x + width - 8 && mouseY >= buttonY && mouseY <= buttonY + 18;
                context.fill(x + width - 140, buttonY, x + width - 74, buttonY + 18, playHover ? 0xFF64A066 : 0xFF528A54);
                context.drawCenteredTextWithShadow(font, "Play", x + width - 107, buttonY + 5, 0xFFFFFFFF);
                context.fill(x + width - 70, buttonY, x + width - 8, buttonY + 18, editHover ? 0xFF3A3A3A : 0xFF2E2E2E);
                context.drawCenteredTextWithShadow(font, "Edit", x + width - 39, buttonY + 5, 0xFFFFFFFF);
            }
            rowY += rowHeight + 4;
        }
    }

    /** @return true if this click was consumed by a row/button in this panel. */
    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        int rowY = y + 30;
        for (WorldListWidget.WorldEntry entry : entries) {
            LevelSummary summary = entry.getLevel();
            boolean expanded = summary.getName().equals(state.expandedRowId());
            int rowHeight = expanded ? ROW_HEIGHT_EXPANDED : ROW_HEIGHT_COMPACT;
            if (rowY + rowHeight > y + height) {
                break;
            }
            if (expanded) {
                int buttonY = rowY + rowHeight - 22;
                if (mouseX >= x + width - 140 && mouseX <= x + width - 74 && mouseY >= buttonY && mouseY <= buttonY + 18) {
                    MainMenuScreen.playClickSound();
                    playWorld(entry);
                    return true;
                }
                if (mouseX >= x + width - 70 && mouseX <= x + width - 8 && mouseY >= buttonY && mouseY <= buttonY + 18) {
                    MainMenuScreen.playClickSound();
                    editWorld(entry);
                    return true;
                }
            }
            if (mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + rowHeight) {
                state.toggleRowExpanded(summary.getName());
                return true;
            }
            rowY += rowHeight + 4;
        }
        return false;
    }

    private void playWorld(WorldListWidget.WorldEntry entry) {
        try {
            entry.play();
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to play world \"" + entry.getLevel().getName() + "\": " + e);
        }
    }

    // FX15 divergence note: unlike fabric-26.1/fabric-26.2 (which call
    // EditWorldScreen.create(...) directly with their own no-op callback --
    // the confirmed root cause there), this platform's dataWidget was built
    // with `owner` (this MainMenuScreen) as its parent Screen
    // (WorldListWidget.Builder(MinecraftClient, owner)), so WorldEntry#edit()
    // -- vanilla's own real button-press implementation, confirmed public via
    // javap -- already navigates back to `owner` internally on both Save and
    // Cancel, the same way vanilla's own SelectWorldScreen does. No callback
    // no-op exists on this platform to fix; FX15 does not apply here.
    private void editWorld(WorldListWidget.WorldEntry entry) {
        try {
            entry.edit();
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to open EditWorldScreen for \"" + entry.getLevel().getName() + "\": " + e);
        }
    }
}
