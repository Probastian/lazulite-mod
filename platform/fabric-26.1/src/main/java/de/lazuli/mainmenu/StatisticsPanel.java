package de.lazuli.mainmenu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.lazuli.CrossWorldStatsBridgeHandoff;
import de.lazuli.LazuliMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Statistics tab panel (batch-2-fixes Item F5, FR-F5.1-5.7; reworked in
 * batch-3-fixes Item BF2 to read a disk-scanned, cross-save, account-scoped
 * total instead of the live in-session {@code StatsCounter}): a Mojang-mapped
 * port of {@code fabric-1.21.11}'s class of the same name. Stat values are
 * read directly from each local save's persisted {@code stats/<uuid>.json}
 * (raw JSON, same shape vanilla itself writes) rather than through
 * {@code net.minecraft.stats.Stats}/{@code StatsCounter} -- no reusable
 * "load a StatsCounter from a save's stats file" client-side API was found
 * (BF2 Risk #1(a); see {@code .claude/context/minecraft.md}'s Known
 * Cross-Version API Differences table).
 */
public final class StatisticsPanel {

    private enum Category { GENERAL, ITEMS, MOBS }

    private enum SortColumn { NAME, A, B, C, D, E, F }

    private static final int PILL_HEIGHT = 18;
    private static final int PILL_GAP = 8;
    private static final int ROW_HEIGHT = 20;
    private static final int SCROLL_STEP = 16;
    private static final int CONTENT_LEFT_PAD = 8;

    private record GeneralRow(String label, String value) { }

    private record ItemRow(Item item, String name, long mined, long broken, long crafted, long used, long pickedUp, long dropped) { }

    private record MobRow(String name, long killedBy, long killed) { }

    private Category category = Category.GENERAL;
    private SortColumn sortColumn = SortColumn.A;
    private boolean sortAscending = false;
    private int scrollOffset;

    private List<GeneralRow> generalRows = List.of();
    private List<ItemRow> itemRows = List.of();
    private List<MobRow> mobRows = List.of();
    private boolean loaded;

    private final LevelStorageSource levelSource = Minecraft.getInstance().getLevelSource();

    /** Throttling state for the "load attempt failed" log line, so retries every render() don't spam the log. */
    private String lastFailureLogSignature;
    private long lastFailureLogTimeMs;
    private static final long FAILURE_LOG_THROTTLE_MS = 1000L;

    /**
     * Called when the Statistics tab becomes active; cheap to call repeatedly
     * (idempotent after first successful load). If any save's stats could not
     * be read due to a transient error (e.g. the save-lock from a just-quit
     * world not yet released), {@code loaded} is left {@code false} so the
     * caller's {@code if (!loaded) reload();} guard in {@link #render} retries
     * on every subsequent render call until a load genuinely succeeds.
     */
    public void reload() {
        Set<String> saveFolderNames = CrossWorldStatsBridgeHandoff.require().localWorldIdsForCurrentAccount();

        // category -> stat key -> summed value, across every resolved local save.
        Map<String, Map<String, Long>> combined = new HashMap<>();
        boolean anyFailure = false;
        if (saveFolderNames.isEmpty()) {
            // Nothing-to-scan case, distinct from "scanned and genuinely
            // empty": CrossWorldStatsBridgeHandoff reported zero local worlds
            // for the current account -- e.g. the just-played world's merge
            // was never recorded into AccountStats.worldBaselines.
            LazuliMod.LOGGER.warn("Statistics tab: CrossWorldStatsBridgeHandoff reported zero local worlds for the current account; nothing to scan.");
        } else {
            for (String saveFolderName : saveFolderNames) {
                if (!accumulateSaveStats(saveFolderName, combined)) {
                    anyFailure = true;
                }
            }
        }

        if (anyFailure) {
            // Genuine transient failure (not just "no data yet") -- leave loaded
            // false so render() retries on the next frame instead of getting
            // permanently stuck on "no statistics yet".
            return;
        }

        if (combined.isEmpty()) {
            generalRows = List.of();
            itemRows = List.of();
            mobRows = List.of();
            loaded = true;
            return;
        }

        Map<String, Long> custom = combined.getOrDefault("minecraft:custom", Map.of());
        List<GeneralRow> general = new ArrayList<>();
        general.add(new GeneralRow("Play Time", formatDuration(custom.getOrDefault("minecraft:play_time", 0L))));
        general.add(new GeneralRow("Distance Walked", formatDistance(custom.getOrDefault("minecraft:walk_one_cm", 0L))));
        general.add(new GeneralRow("Distance Sprinted", formatDistance(custom.getOrDefault("minecraft:sprint_one_cm", 0L))));
        general.add(new GeneralRow("Distance Flown", formatDistance(custom.getOrDefault("minecraft:aviate_one_cm", 0L))));
        general.add(new GeneralRow("Jumps", String.valueOf(custom.getOrDefault("minecraft:jump", 0L))));
        general.add(new GeneralRow("Damage Dealt", formatHealth(custom.getOrDefault("minecraft:damage_dealt", 0L))));
        general.add(new GeneralRow("Damage Taken", formatHealth(custom.getOrDefault("minecraft:damage_taken", 0L))));
        general.add(new GeneralRow("Deaths", String.valueOf(custom.getOrDefault("minecraft:deaths", 0L))));
        general.add(new GeneralRow("Mob Kills", String.valueOf(custom.getOrDefault("minecraft:mob_kills", 0L))));
        general.add(new GeneralRow("Player Kills", String.valueOf(custom.getOrDefault("minecraft:player_kills", 0L))));
        general.add(new GeneralRow("Times Slept", String.valueOf(custom.getOrDefault("minecraft:sleep_in_bed", 0L))));
        general.add(new GeneralRow("Raids Won", String.valueOf(custom.getOrDefault("minecraft:raid_win", 0L))));
        this.generalRows = general;

        Map<String, Long> crafted = combined.getOrDefault("minecraft:crafted", Map.of());
        Map<String, Long> used = combined.getOrDefault("minecraft:used", Map.of());
        Map<String, Long> broken = combined.getOrDefault("minecraft:broken", Map.of());
        Map<String, Long> pickedUp = combined.getOrDefault("minecraft:picked_up", Map.of());
        Map<String, Long> dropped = combined.getOrDefault("minecraft:dropped", Map.of());
        Map<String, Long> mined = combined.getOrDefault("minecraft:mined", Map.of());

        List<ItemRow> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            String itemKey = BuiltInRegistries.ITEM.getKey(item).toString();
            long craftedV = crafted.getOrDefault(itemKey, 0L);
            long usedV = used.getOrDefault(itemKey, 0L);
            long brokenV = broken.getOrDefault(itemKey, 0L);
            long pickedUpV = pickedUp.getOrDefault(itemKey, 0L);
            long droppedV = dropped.getOrDefault(itemKey, 0L);
            long minedV = 0;
            var block = net.minecraft.world.level.block.Block.byItem(item);
            if (block != Blocks.AIR) {
                minedV = mined.getOrDefault(BuiltInRegistries.BLOCK.getKey(block).toString(), 0L);
            }
            if (craftedV == 0 && usedV == 0 && brokenV == 0 && pickedUpV == 0 && droppedV == 0 && minedV == 0) {
                continue; // FR-F5.6: omit all-zero rows.
            }
            String name = Component.translatable(item.getDescriptionId()).getString();
            items.add(new ItemRow(item, name, minedV, brokenV, craftedV, usedV, pickedUpV, droppedV));
        }
        this.itemRows = items;

        Map<String, Long> killedBy = combined.getOrDefault("minecraft:killed_by", Map.of());
        Map<String, Long> killed = combined.getOrDefault("minecraft:killed", Map.of());
        List<MobRow> mobs = new ArrayList<>();
        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
            String entityKey = BuiltInRegistries.ENTITY_TYPE.getKey(entityType).toString();
            long killedByV = killedBy.getOrDefault(entityKey, 0L);
            long killedV = killed.getOrDefault(entityKey, 0L);
            if (killedByV == 0 && killedV == 0) {
                continue; // FR-F5.6: omit all-zero rows.
            }
            mobs.add(new MobRow(entityType.getDescription().getString(), killedByV, killedV));
        }
        this.mobRows = mobs;

        loaded = true;
        applySort();
    }

    /**
     * Reads {@code <save>/stats/<uuid>.json} (raw JSON, same shape vanilla
     * itself writes) and accumulates every category/key value into
     * {@code combined}. A save with no stats file yet (legitimately empty,
     * e.g. brand-new world) contributes nothing and is still a success. An
     * unreadable/malformed save (e.g. a save-lock still held moments after
     * quitting) is treated as a transient failure: it is logged (throttled)
     * and {@code false} is returned so the caller retries on the next
     * {@code render()} instead of assuming "no statistics".
     *
     * @return {@code true} if this save was read successfully (with or
     *         without data), {@code false} if a genuine load error occurred.
     */
    private boolean accumulateSaveStats(String saveFolderName, Map<String, Map<String, Long>> combined) {
        try (LevelStorageSource.LevelStorageAccess access = levelSource.createAccess(saveFolderName)) {
            Path statsDir = access.getLevelPath(LevelResource.PLAYER_STATS_DIR);
            if (!Files.isDirectory(statsDir)) {
                return true;
            }
            try (var statsFiles = Files.list(statsDir)) {
                for (Path statsFile : (Iterable<Path>) statsFiles.filter(p -> p.toString().endsWith(".json"))::iterator) {
                    try (Reader reader = Files.newBufferedReader(statsFile)) {
                        JsonElement root = JsonParser.parseReader(reader);
                        if (!root.isJsonObject()) {
                            continue;
                        }
                        JsonElement statsElement = root.getAsJsonObject().get("stats");
                        if (statsElement == null || !statsElement.isJsonObject()) {
                            continue;
                        }
                        for (Map.Entry<String, JsonElement> categoryEntry : statsElement.getAsJsonObject().entrySet()) {
                            if (!categoryEntry.getValue().isJsonObject()) {
                                continue;
                            }
                            Map<String, Long> categoryMap = combined.computeIfAbsent(categoryEntry.getKey(), k -> new HashMap<>());
                            for (Map.Entry<String, JsonElement> statEntry : categoryEntry.getValue().getAsJsonObject().entrySet()) {
                                if (!statEntry.getValue().isJsonPrimitive()) {
                                    continue;
                                }
                                long value = statEntry.getValue().getAsLong();
                                categoryMap.merge(statEntry.getKey(), value, Long::sum);
                            }
                        }
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            // Likely transient (e.g. save-lock not yet released after a recent
            // disconnect); caller retries on the next render() while !loaded.
            logFailureThrottled(saveFolderName, e);
            return false;
        }
    }

    private void logFailureThrottled(String saveFolderName, Exception e) {
        String signature = e.getClass().getName() + ":" + e.getMessage();
        long now = System.currentTimeMillis();
        if (signature.equals(lastFailureLogSignature) && (now - lastFailureLogTimeMs) < FAILURE_LOG_THROTTLE_MS) {
            return;
        }
        lastFailureLogSignature = signature;
        lastFailureLogTimeMs = now;
        LazuliMod.LOGGER.warn("Failed to read statistics for save \"" + saveFolderName + "\" (will retry): " + e);
    }

    private void applySort() {
        Comparator<ItemRow> itemCmp = switch (sortColumn) {
            case A -> Comparator.comparingLong(ItemRow::mined);
            case B -> Comparator.comparingLong(ItemRow::broken);
            case C -> Comparator.comparingLong(ItemRow::crafted);
            case D -> Comparator.comparingLong(ItemRow::used);
            case E -> Comparator.comparingLong(ItemRow::pickedUp);
            case F -> Comparator.comparingLong(ItemRow::dropped);
            case NAME -> Comparator.comparing(ItemRow::name);
        };
        Comparator<MobRow> mobCmp = switch (sortColumn) {
            case A -> Comparator.comparingLong(MobRow::killedBy);
            case B -> Comparator.comparingLong(MobRow::killed);
            default -> Comparator.comparing(MobRow::name);
        };
        List<ItemRow> items = new ArrayList<>(itemRows);
        items.sort(sortAscending ? itemCmp : itemCmp.reversed());
        this.itemRows = items;
        List<MobRow> mobs = new ArrayList<>(mobRows);
        mobs.sort(sortAscending ? mobCmp : mobCmp.reversed());
        this.mobRows = mobs;
    }

    public void render(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        if (!loaded) {
            reload();
        }
        guiGraphics.text(font, Component.literal("Statistics"), x + CONTENT_LEFT_PAD, y, 0xFFEAE8E1);

        int pillY = y + 30;
        int pillX = x + CONTENT_LEFT_PAD;
        for (Category c : Category.values()) {
            String label = categoryLabel(c);
            int pillWidth = font.width(label) + 16;
            boolean active = c == category;
            boolean hovered = mouseX >= pillX && mouseX <= pillX + pillWidth && mouseY >= pillY && mouseY <= pillY + PILL_HEIGHT;
            guiGraphics.fill(pillX, pillY, pillX + pillWidth, pillY + PILL_HEIGHT, active ? 0xFF528A54 : (hovered ? 0xFF2A2820 : 0xFF201E17));
            guiGraphics.centeredText(font, Component.literal(label), pillX + pillWidth / 2, pillY + 5, 0xFFEAE8E1);
            pillX += pillWidth + PILL_GAP;
        }

        int contentY = pillY + PILL_HEIGHT + 10;
        int contentHeight = y + height - contentY;
        switch (category) {
            case GENERAL -> renderGeneral(guiGraphics, font, x, contentY, width, contentHeight);
            case ITEMS -> renderItems(guiGraphics, font, x, contentY, width, contentHeight, mouseX, mouseY);
            case MOBS -> renderMobs(guiGraphics, font, x, contentY, width, contentHeight, mouseX, mouseY);
        }
    }

    private void renderGeneral(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height) {
        int rowY = y;
        for (GeneralRow row : generalRows) {
            if (rowY + ROW_HEIGHT > y + height) {
                break;
            }
            guiGraphics.fill(x + CONTENT_LEFT_PAD, rowY + 3, x + CONTENT_LEFT_PAD + 8, rowY + 15, 0xFF528A54);
            guiGraphics.text(font, Component.literal(row.label()), x + CONTENT_LEFT_PAD + 14, rowY + 4, 0xFFEAE8E1);
            int valueWidth = font.width(row.value());
            guiGraphics.text(font, Component.literal(row.value()), x + width - valueWidth - 4, rowY + 4, 0xFF908C7F);
            rowY += ROW_HEIGHT;
        }
    }

    private static int colX(int x, int width, int columnCount, int colIndex) {
        int blockWidth = columnCount * COL_WIDTH;
        int minNameColRight = x + CONTENT_LEFT_PAD + MIN_NAME_COL_WIDTH;
        int anchoredStart = x + width - blockWidth;
        int start = Math.max(anchoredStart, minNameColRight);
        int available = x + width - start;
        if (available < blockWidth && blockWidth > 0) {
            // Compress proportionally rather than overflow past x + width.
            return start + (int) Math.round(colIndex * (available / (double) columnCount));
        }
        return start + colIndex * COL_WIDTH;
    }

    private static final int COL_WIDTH = 60;
    private static final int MIN_NAME_COL_WIDTH = 80;

    private void renderItems(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        renderTableHeader(guiGraphics, font, x, y, width, 6, new String[] { "Mined", "Broken", "Crafted", "Used", "Picked Up", "Dropped" });
        int viewportTop = y + 16;
        int viewportBottom = y + height;
        scrollOffset = clampScroll(scrollOffset, itemRows.size(), viewportBottom - viewportTop);
        guiGraphics.enableScissor(x, viewportTop, x + width, viewportBottom);
        int rowY = viewportTop - scrollOffset;
        for (ItemRow row : itemRows) {
            if (rowY + ROW_HEIGHT > viewportTop && rowY < viewportBottom) {
                boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT;
                guiGraphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, hovered ? 0xFF2A2820 : 0xFF201E17);
                try {
                    guiGraphics.item(new ItemStack(row.item()), x + CONTENT_LEFT_PAD, rowY + 2);
                } catch (RuntimeException ignored) {
                    // Some registered items' components can be unbound at render time
                    // (e.g. certain placeholder/template entries) -- skip the icon
                    // rather than crashing the whole screen over cosmetic art.
                }
                guiGraphics.text(font, Component.literal(row.name()), x + CONTENT_LEFT_PAD + 16, rowY + 6, 0xFFEAE8E1);
                drawColValue(guiGraphics, font, x, width, rowY, 6, 0, row.mined());
                drawColValue(guiGraphics, font, x, width, rowY, 6, 1, row.broken());
                drawColValue(guiGraphics, font, x, width, rowY, 6, 2, row.crafted());
                drawColValue(guiGraphics, font, x, width, rowY, 6, 3, row.used());
                drawColValue(guiGraphics, font, x, width, rowY, 6, 4, row.pickedUp());
                drawColValue(guiGraphics, font, x, width, rowY, 6, 5, row.dropped());
            }
            rowY += ROW_HEIGHT;
        }
        guiGraphics.disableScissor();
        if (itemRows.isEmpty()) {
            guiGraphics.text(font, Component.literal("No item statistics yet."), x + CONTENT_LEFT_PAD, viewportTop, 0xFF908C7F);
        }
    }

    private void renderMobs(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int height, int mouseX, int mouseY) {
        renderTableHeader(guiGraphics, font, x, y, width, 2, new String[] { "Killed By", "Killed" });
        int viewportTop = y + 16;
        int viewportBottom = y + height;
        scrollOffset = clampScroll(scrollOffset, mobRows.size(), viewportBottom - viewportTop);
        guiGraphics.enableScissor(x, viewportTop, x + width, viewportBottom);
        int rowY = viewportTop - scrollOffset;
        for (MobRow row : mobRows) {
            if (rowY + ROW_HEIGHT > viewportTop && rowY < viewportBottom) {
                boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT;
                guiGraphics.fill(x, rowY, x + width, rowY + ROW_HEIGHT, hovered ? 0xFF2A2820 : 0xFF201E17);
                guiGraphics.fill(x + CONTENT_LEFT_PAD + 2, rowY + 3, x + CONTENT_LEFT_PAD + 10, rowY + 15, 0xFFB54848);
                guiGraphics.text(font, Component.literal(row.name()), x + CONTENT_LEFT_PAD + 16, rowY + 6, 0xFFEAE8E1);
                drawColValue(guiGraphics, font, x, width, rowY, 2, 0, row.killedBy());
                drawColValue(guiGraphics, font, x, width, rowY, 2, 1, row.killed());
            }
            rowY += ROW_HEIGHT;
        }
        guiGraphics.disableScissor();
        if (mobRows.isEmpty()) {
            guiGraphics.text(font, Component.literal("No mob statistics yet."), x + CONTENT_LEFT_PAD, viewportTop, 0xFF908C7F);
        }
    }

    private void drawColValue(GuiGraphicsExtractor guiGraphics, Font font, int x, int width, int rowY, int columnCount, int colIndex, long value) {
        String text = String.valueOf(value);
        int col = colX(x, width, columnCount, colIndex);
        guiGraphics.text(font, Component.literal(text), col, rowY + 6, 0xFFEAE8E1);
    }

    private void renderTableHeader(GuiGraphicsExtractor guiGraphics, Font font, int x, int y, int width, int columnCount, String[] labels) {
        for (int i = 0; i < labels.length; i++) {
            SortColumn col = SortColumn.values()[i + 1];
            boolean sorted = sortColumn == col;
            String text = sorted ? labels[i] + (sortAscending ? " ▲" : " ▼") : labels[i];
            guiGraphics.text(font, Component.literal(text), colX(x, width, columnCount, i), y, sorted ? 0xFFC9A227 : 0xFFEAE8E1);
        }
    }

    private static int clampScroll(int offset, int rowCount, int viewportHeight) {
        int contentHeight = rowCount * ROW_HEIGHT;
        int maxScroll = Math.max(0, contentHeight - Math.max(0, viewportHeight));
        return Math.max(0, Math.min(offset, maxScroll));
    }

    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        int pillY = y + 30;
        int pillX = x + CONTENT_LEFT_PAD;
        Font font = Minecraft.getInstance().font;
        for (Category c : Category.values()) {
            String label = categoryLabel(c);
            int pillWidth = font.width(label) + 16;
            if (mouseX >= pillX && mouseX <= pillX + pillWidth && mouseY >= pillY && mouseY <= pillY + PILL_HEIGHT) {
                MainMenuScreen.playClickSound();
                category = c;
                scrollOffset = 0;
                return true;
            }
            pillX += pillWidth + PILL_GAP;
        }

        if (category == Category.GENERAL) {
            return false;
        }
        int contentY = pillY + PILL_HEIGHT + 10;
        int columnCount = category == Category.ITEMS ? 6 : 2;
        if (mouseY >= contentY - 12 && mouseY < contentY + 4) {
            for (int i = 0; i < columnCount; i++) {
                int col = colX(x, width, columnCount, i);
                if (mouseX >= col && mouseX < col + COL_WIDTH) {
                    MainMenuScreen.playClickSound();
                    SortColumn clicked = SortColumn.values()[i + 1];
                    sortAscending = sortColumn == clicked && !sortAscending;
                    sortColumn = clicked;
                    applySort();
                    return true;
                }
            }
        }
        return false;
    }

    public boolean mouseScrolled(int x, int y, int width, int height, double mouseX, double mouseY, double scrollDelta) {
        if (category == Category.GENERAL) {
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

    private static String categoryLabel(Category c) {
        return switch (c) {
            case GENERAL -> "General";
            case ITEMS -> "Items";
            case MOBS -> "Mobs";
        };
    }

    private static String formatDuration(long ticks) {
        long totalSeconds = ticks / 20;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    private static String formatDistance(long centimeters) {
        double meters = centimeters / 100.0;
        if (meters >= 1000) {
            return String.format("%.1f km", meters / 1000.0);
        }
        return String.format("%.0f m", meters);
    }

    private static String formatHealth(long tenthsOfHeart) {
        return String.format("%.1f", tenthsOfHeart / 10.0);
    }
}
