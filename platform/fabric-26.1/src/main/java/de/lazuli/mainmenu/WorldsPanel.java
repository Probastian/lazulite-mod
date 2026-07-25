package de.lazuli.mainmenu;

import de.lazuli.features.mainmenu.services.MainMenuStateMachine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Worlds tab panel (specification FR3): real vanilla saved-world data (not a
 * mock), single-expand accordion rows (FR3.3), Create New World opening the
 * real vanilla {@link CreateWorldScreen} (FR3.5, no toast placeholder),
 * Play/Edit delegating to vanilla's own real world-load/edit flows
 * (FR3.6/FR3.7).
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
    private static int[] pillBounds(Font font, int x, int width) {
        int playW = font.width("Play") + PILL_PADDING * 2;
        int editW = font.width("Edit") + PILL_PADDING * 2;
        int editX = x + width - 8 - editW;
        int playX = editX - PILL_GAP - playW;
        return new int[] { playX, playW, editX, editW };
    }

    private final MainMenuStateMachine state;
    private final MainMenuScreen owner;
    private final LevelStorageSource levelSource = Minecraft.getInstance().getLevelSource();
    private final IconTextureCache iconCache = new IconTextureCache(de.lazuli.LazuliMod.LOGGER::warn);
    private volatile List<LevelSummary> summaries = List.of();
    private volatile boolean loading = true;
    private Button createButton;
    private boolean tabActive;

    public WorldsPanel(MainMenuStateMachine state, MainMenuScreen owner) {
        this.state = state;
        this.owner = owner;
        reload();
    }

    private void reload() {
        loading = true;
        iconCache.invalidateAll();
        try {
            LevelStorageSource.LevelCandidates candidates = levelSource.findLevelCandidates();
            CompletableFuture<List<LevelSummary>> future = levelSource.loadLevelSummaries(candidates);
            future.thenAcceptAsync(loaded -> {
                List<LevelSummary> sorted = new ArrayList<>(loaded);
                sorted.sort(null);
                this.summaries = sorted;
                this.loading = false;
            }, Minecraft.getInstance());
        } catch (Exception e) {
            summaries = List.of();
            loading = false;
        }
    }

    /** Called once, when the tab bar/screen constructs the panel's own buttons. */
    public void init(java.util.function.Consumer<Button> addWidget, int x, int y, int width) {
        createButton = Button.builder(Component.literal("+ Create New World"), b -> {
            CreateWorldScreen.openFresh(Minecraft.getInstance(), () -> Minecraft.getInstance().setScreenAndShow(owner));
        }).bounds(x + width - 160, y, 160, 20).build();
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

    public void render(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        int leftX = x + CONTENT_LEFT_PAD;
        guiGraphics.text(font, Component.literal("Singleplayer Worlds"), leftX, y + 6, 0xFFEAE8E1);

        int rowY = y + 30;
        if (loading) {
            guiGraphics.text(font, Component.literal("Loading worlds..."), leftX, rowY, 0xFF908C7F);
            return;
        }
        if (summaries.isEmpty()) {
            guiGraphics.text(font, Component.literal("No saved worlds yet."), leftX, rowY, 0xFF908C7F);
            return;
        }

        for (LevelSummary summary : summaries) {
            boolean expanded = summary.getLevelId().equals(state.expandedRowId());
            int rowHeight = expanded ? ROW_HEIGHT_EXPANDED : ROW_HEIGHT_COMPACT;
            if (rowY + rowHeight > y + height) {
                break;
            }
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + rowHeight;
            guiGraphics.fill(x, rowY, x + width, rowY + rowHeight, hovered ? 0xFF2A2820 : 0xFF201E17);

            Identifier iconId = iconCache.forWorld(summary.getLevelId(), summary.getIcon());
            int textX;
            if (expanded) {
                // FR-B3.1: expanded row shows the world icon scaled up to fill
                // the larger thumbnail area (single real icon, no repeated tiles).
                int gridSize = rowHeight - IMAGE_MARGIN * 2;
                int gridX = leftX + IMAGE_MARGIN;
                int gridY = rowY + IMAGE_MARGIN;
                guiGraphics.blit(RenderPipelines.GUI_TEXTURED, iconId, gridX, gridY, 0f, 0f,
                        gridSize, gridSize, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE);
                textX = gridX + gridSize + 6;
            } else {
                // FX13.1/FX13.2: real-or-fallback world icon thumbnail
                // (FaviconTexture already resolves to a "missing" sprite until
                // upload() succeeds). Sized to 2/3 of the row height rather than
                // the full row (full-height read as oversized against the text),
                // 1:1, no border, and vertically centered in the leftover space.
                int iconSize = (rowHeight - IMAGE_MARGIN * 2) * 2 / 3;
                int iconX = leftX + IMAGE_MARGIN;
                int iconY = rowY + (rowHeight - iconSize) / 2;
                guiGraphics.blit(RenderPipelines.GUI_TEXTURED, iconId, iconX, iconY, 0f, 0f,
                        iconSize, iconSize, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE);
                textX = iconX + iconSize + 6;
            }

            guiGraphics.text(font, Component.literal(summary.getLevelName()), textX, rowY + 4, 0xFFEAE8E1);
            String subtitle = summary.getGameMode().getLongDisplayName().getString()
                    + " · " + relativeTime(summary.getLastPlayed());
            guiGraphics.text(font, Component.literal(subtitle), textX, rowY + 15, 0xFF908C7F);

            if (expanded) {
                int buttonY = rowY + rowHeight - 22;
                int[] bounds = pillBounds(font, x, width);
                int playX = bounds[0], playW = bounds[1], editX = bounds[2], editW = bounds[3];
                boolean playHover = mouseX >= playX && mouseX <= playX + playW && mouseY >= buttonY && mouseY <= buttonY + 18;
                boolean editHover = mouseX >= editX && mouseX <= editX + editW && mouseY >= buttonY && mouseY <= buttonY + 18;
                guiGraphics.fill(playX, buttonY, playX + playW, buttonY + 18, playHover ? 0xFF64A066 : 0xFF528A54);
                guiGraphics.centeredText(font, Component.literal("Play"), playX + playW / 2, buttonY + 5, 0xFFFFFFFF);
                guiGraphics.fill(editX, buttonY, editX + editW, buttonY + 18, editHover ? 0xFF3A3A3A : 0xFF2E2E2E);
                guiGraphics.centeredText(font, Component.literal("Edit"), editX + editW / 2, buttonY + 5, 0xFFFFFFFF);
            }
            rowY += rowHeight + 4;
        }
    }

    /** @return true if this click was consumed by a row/button in this panel. */
    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        int rowY = y + 30;
        for (LevelSummary summary : summaries) {
            boolean expanded = summary.getLevelId().equals(state.expandedRowId());
            int rowHeight = expanded ? ROW_HEIGHT_EXPANDED : ROW_HEIGHT_COMPACT;
            if (rowY + rowHeight > y + height) {
                break;
            }
            if (expanded) {
                int buttonY = rowY + rowHeight - 22;
                int[] bounds = pillBounds(Minecraft.getInstance().font, x, width);
                int playX = bounds[0], playW = bounds[1], editX = bounds[2], editW = bounds[3];
                if (mouseX >= playX && mouseX <= playX + playW && mouseY >= buttonY && mouseY <= buttonY + 18) {
                    MainMenuScreen.playClickSound();
                    playWorld(summary);
                    return true;
                }
                if (mouseX >= editX && mouseX <= editX + editW && mouseY >= buttonY && mouseY <= buttonY + 18) {
                    MainMenuScreen.playClickSound();
                    editWorld(summary);
                    return true;
                }
            }
            if (mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + rowHeight) {
                state.toggleRowExpanded(summary.getLevelId());
                return true;
            }
            rowY += rowHeight + 4;
        }
        return false;
    }

    /** Batch-2-fixes FR-F4.2: real saved-world data for Home's Recent section (already sorted most-recent-first, natural ordering). */
    List<LevelSummary> recentEntries() {
        return summaries;
    }

    /** Batch-2-fixes FR-F4.2: package-private so {@code HomePanel} can invoke the same real play action a Worlds row click does. */
    void playWorld(LevelSummary summary) {
        Minecraft.getInstance().createWorldOpenFlows().openWorld(summary.getLevelId(), () -> { });
    }

    private void editWorld(LevelSummary summary) {
        try {
            LevelStorageSource.LevelStorageAccess access = levelSource.createAccess(summary.getLevelId());
            // FX15: EditWorldScreen.onClose() (Cancel/ESC) simply calls
            // callback.accept(false) with no screen transition of its own --
            // this callback is the sole place that navigates back, mirroring
            // the "+ Create New World" reference pattern below. Unlike that
            // reference (confirmed it does not call reload() explicitly),
            // this callback does call reload() explicitly (FX15.3): since
            // `owner`/this WorldsPanel instance is not reconstructed on
            // setScreenAndShow(owner) (same MainMenuScreen), a renamed
            // world's new name would not otherwise reflect in the list
            // without an explicit reload -- a deliberate small deviation
            // from mirroring the create-flow 1:1, needed to satisfy the
            // spec's own acceptance criterion that Save's changes show up.
            // EditWorldScreen never closes the LevelStorageAccess it's handed
            // (confirmed via javap: neither onClose() nor the Save path calls
            // access.close()) -- the caller owns that lifecycle. Leaving it
            // open kept the world's directory lock held past this screen's
            // lifetime, so the reload() below raced with the still-held lock
            // and threw OverlappingFileLockException, dropping the world from
            // the reloaded list. Close it here before reloading.
            EditWorldScreen editScreen = EditWorldScreen.create(Minecraft.getInstance(), access,
                    backedUp -> {
                        try {
                            access.close();
                        } catch (Exception closeEx) {
                            de.lazuli.LazuliMod.LOGGER.warn("Failed to close level access for " + summary.getLevelId() + ": " + closeEx);
                        }
                        Minecraft.getInstance().setScreenAndShow(owner);
                        reload();
                    });
            Minecraft.getInstance().setScreenAndShow(editScreen);
        } catch (Exception e) {
            de.lazuli.LazuliMod.LOGGER.warn("Failed to open EditWorldScreen for " + summary.getLevelId() + ": " + e);
        }
    }
}
