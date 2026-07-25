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
    private static final int PILL_PADDING = 10;
    private static final int PILL_GAP = 10;

    /**
     * Bug-fix (post-launch-fixes-3, FR-B3.2): "Last played" must read as a
     * relative time from the world's own last-played timestamp (spec FR3.4),
     * not an absolute calendar date -- the previous {@code LAST_PLAYED_FORMAT}
     * (a {@code DateTimeFormatter}) was a direct violation of that already-
     * approved requirement.
     */
    static String relativeTime(long epochMillis) {
        long diffMs = Math.max(0, System.currentTimeMillis() - epochMillis);
        long minutes = diffMs / 60_000L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        if (days > 0) {
            return days + (days == 1 ? " day ago" : " days ago");
        }
        if (hours > 0) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        if (minutes > 0) {
            return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        }
        return "just now";
    }

    /**
     * Bug-fix (post-launch-fixes-3, FR-B3.3): Play/Edit width computed from
     * label width + {@link #PILL_PADDING} on each side (rather than fixed
     * 66px/62px blocks), separated by {@link #PILL_GAP} so they read as two
     * distinct pill buttons. Shared by {@code render()} and {@code
     * mouseClicked()} so the two never drift out of sync with each other.
     */
    private static int[] pillBounds(TextRenderer font, int x, int width) {
        int playW = font.getWidth("Play") + PILL_PADDING * 2;
        int editW = font.getWidth("Edit") + PILL_PADDING * 2;
        int editX = x + width - 8 - editW;
        int playX = editX - PILL_GAP - playW;
        return new int[] { playX, playW, editX, editW };
    }

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

    private static final int CONTENT_LEFT_PAD = 8;

    public void render(DrawContext context, TextRenderer font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int leftX = x + CONTENT_LEFT_PAD;
        context.drawText(font, Text.literal("Singleplayer Worlds"), leftX, y + 6, 0xFFEAE8E1, false);

        int rowY = y + 30;
        if (entries.isEmpty()) {
            context.drawText(font, Text.literal("No saved worlds yet."), leftX, rowY, 0xFF908C7F, false);
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

            Identifier iconId = iconCache.forWorld(summary.getName(), summary.getIconPath());
            int textX;
            if (expanded) {
                // FR-B3.1: expanded row shows the world icon scaled up to fill
                // the larger thumbnail area (single real icon, no repeated tiles).
                int gridSize = rowHeight - IMAGE_MARGIN * 2;
                int gridX = leftX + IMAGE_MARGIN;
                int gridY = rowY + IMAGE_MARGIN;
                context.drawTexture(RenderPipelines.GUI_TEXTURED, iconId, gridX, gridY, 0f, 0f,
                        gridSize, gridSize, ICON_TEX_SIZE, ICON_TEX_SIZE);
                textX = gridX + gridSize + 6;
            } else {
                // FX13.1/FX13.2: real-or-fallback world icon thumbnail, sized to
                // 2/3 of the row height (full-height read as oversized against
                // the text), 1:1, no border, vertically centered.
                int iconSize = (rowHeight - IMAGE_MARGIN * 2) * 2 / 3;
                int iconX = leftX + IMAGE_MARGIN;
                int iconY = rowY + (rowHeight - iconSize) / 2;
                context.drawTexture(RenderPipelines.GUI_TEXTURED, iconId, iconX, iconY, 0f, 0f,
                        iconSize, iconSize, ICON_TEX_SIZE, ICON_TEX_SIZE);
                textX = iconX + iconSize + 6;
            }

            context.drawText(font, Text.literal(entry.getLevelDisplayName()), textX, rowY + 4, 0xFFEAE8E1, false);
            String subtitle = summary.getGameMode().getTranslatableName().getString() + " · "
                    + relativeTime(summary.getLastPlayed());
            context.drawText(font, Text.literal(subtitle), textX, rowY + 15, 0xFF908C7F, false);

            if (expanded) {
                int buttonY = rowY + rowHeight - 22;
                int[] bounds = pillBounds(font, x, width);
                int playX = bounds[0], playW = bounds[1], editX = bounds[2], editW = bounds[3];
                boolean playHover = mouseX >= playX && mouseX <= playX + playW && mouseY >= buttonY && mouseY <= buttonY + 18;
                boolean editHover = mouseX >= editX && mouseX <= editX + editW && mouseY >= buttonY && mouseY <= buttonY + 18;
                context.fill(playX, buttonY, playX + playW, buttonY + 18, playHover ? 0xFF64A066 : 0xFF528A54);
                context.drawCenteredTextWithShadow(font, "Play", playX + playW / 2, buttonY + 5, 0xFFFFFFFF);
                context.fill(editX, buttonY, editX + editW, buttonY + 18, editHover ? 0xFF3A3A3A : 0xFF2E2E2E);
                context.drawCenteredTextWithShadow(font, "Edit", editX + editW / 2, buttonY + 5, 0xFFFFFFFF);
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
                int[] bounds = pillBounds(MinecraftClient.getInstance().textRenderer, x, width);
                int playX = bounds[0], playW = bounds[1], editX = bounds[2], editW = bounds[3];
                if (mouseX >= playX && mouseX <= playX + playW && mouseY >= buttonY && mouseY <= buttonY + 18) {
                    MainMenuScreen.playClickSound();
                    playWorld(entry);
                    return true;
                }
                if (mouseX >= editX && mouseX <= editX + editW && mouseY >= buttonY && mouseY <= buttonY + 18) {
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

    /** Batch-2-fixes FR-F4.2: real saved-world data for Home's Recent section, already loaded, no new read. */
    List<WorldListWidget.WorldEntry> recentEntries() {
        return entries;
    }

    /** Batch-2-fixes FR-F4.2: package-private so {@code HomePanel} can invoke the same real play action a Worlds row click does. */
    void playWorld(WorldListWidget.WorldEntry entry) {
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
