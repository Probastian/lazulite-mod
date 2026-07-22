package de.lazuli.mainmenu;

import de.lazuli.features.mainmenu.services.MainMenuStateMachine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    private static final int ROW_HEIGHT_COMPACT = 24;
    private static final int ROW_HEIGHT_EXPANDED = 64;
    private static final DateTimeFormatter LAST_PLAYED_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final MainMenuStateMachine state;
    private final MainMenuScreen owner;
    private final LevelStorageSource levelSource = Minecraft.getInstance().getLevelSource();
    private volatile List<LevelSummary> summaries = List.of();
    private volatile boolean loading = true;
    private Button createButton;

    public WorldsPanel(MainMenuStateMachine state, MainMenuScreen owner) {
        this.state = state;
        this.owner = owner;
        reload();
    }

    private void reload() {
        loading = true;
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
        addWidget.accept(createButton);
    }

    public void render(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        guiGraphics.text(font, Component.literal("Singleplayer Worlds"), x, y + 6, 0xFFEAE8E1);

        int rowY = y + 30;
        if (loading) {
            guiGraphics.text(font, Component.literal("Loading worlds..."), x, rowY, 0xFF908C7F);
            return;
        }
        if (summaries.isEmpty()) {
            guiGraphics.text(font, Component.literal("No saved worlds yet."), x, rowY, 0xFF908C7F);
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
            guiGraphics.text(font, Component.literal(summary.getLevelName()), x + 8, rowY + 4, 0xFFEAE8E1);
            String subtitle = summary.getGameMode().getLongDisplayName().getString()
                    + " · " + LAST_PLAYED_FORMAT.format(Instant.ofEpochMilli(summary.getLastPlayed()));
            guiGraphics.text(font, Component.literal(subtitle), x + 8, rowY + 15, 0xFF908C7F);

            if (expanded) {
                int buttonY = rowY + rowHeight - 22;
                boolean playHover = mouseX >= x + width - 140 && mouseX <= x + width - 74 && mouseY >= buttonY && mouseY <= buttonY + 18;
                boolean editHover = mouseX >= x + width - 70 && mouseX <= x + width - 8 && mouseY >= buttonY && mouseY <= buttonY + 18;
                guiGraphics.fill(x + width - 140, buttonY, x + width - 74, buttonY + 18, playHover ? 0xFF64A066 : 0xFF528A54);
                guiGraphics.centeredText(font, Component.literal("Play"), x + width - 107, buttonY + 5, 0xFFFFFFFF);
                guiGraphics.fill(x + width - 70, buttonY, x + width - 8, buttonY + 18, editHover ? 0xFF3A3A3A : 0xFF2E2E2E);
                guiGraphics.centeredText(font, Component.literal("Edit"), x + width - 39, buttonY + 5, 0xFFFFFFFF);
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
                if (mouseX >= x + width - 140 && mouseX <= x + width - 74 && mouseY >= buttonY && mouseY <= buttonY + 18) {
                    playWorld(summary);
                    return true;
                }
                if (mouseX >= x + width - 70 && mouseX <= x + width - 8 && mouseY >= buttonY && mouseY <= buttonY + 18) {
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

    private void playWorld(LevelSummary summary) {
        Minecraft.getInstance().createWorldOpenFlows().openWorld(summary.getLevelId(), () -> { });
    }

    private void editWorld(LevelSummary summary) {
        try {
            LevelStorageSource.LevelStorageAccess access = levelSource.createAccess(summary.getLevelId());
            EditWorldScreen editScreen = EditWorldScreen.create(Minecraft.getInstance(), access, backedUp -> { });
            Minecraft.getInstance().setScreenAndShow(editScreen);
        } catch (Exception e) {
            de.lazuli.LazuliMod.LOGGER.warn("Failed to open EditWorldScreen for " + summary.getLevelId() + ": " + e);
        }
    }
}
