package de.lazuli.mainmenu;

import de.lazuli.CloudOnlyWorldsHookHolder;
import de.lazuli.LazuliMod;
import de.lazuli.WorldConflictHookHolder;
import de.lazuli.WorldFreshnessHookHolder;
import de.lazuli.WorldRestoreHookHolder;
import de.lazuli.WorldSyncStatusHookHolder;
import de.lazuli.WorldSyncToggleHookHolder;
import de.lazuli.api.cloudsync.CloudOnlyWorldSummary;
import de.lazuli.api.cloudsync.CloudOnlyWorldsHook;
import de.lazuli.api.cloudsync.WorldConflictHook;
import de.lazuli.api.cloudsync.WorldConflictHook.ConflictStatus;
import de.lazuli.api.cloudsync.WorldConflictResolutionHook;
import de.lazuli.api.cloudsync.WorldFreshnessHook;
import de.lazuli.api.cloudsync.WorldFreshnessHook.FreshnessDetail;
import de.lazuli.api.cloudsync.WorldFreshnessHook.UpToDateStatus;
import de.lazuli.api.cloudsync.WorldRestoreHook;
import de.lazuli.api.cloudsync.WorldSyncStatusHook;
import de.lazuli.api.cloudsync.WorldSyncStatusHook.SyncStatus;
import de.lazuli.api.cloudsync.WorldSyncToggleHook;
import de.lazuli.cloudsync.WorldConflictScreen;
import de.lazuli.cloudsync.WorldRestoreScreen;
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
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.level.storage.LevelSummary;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    // FR-A.3/UI: ported verbatim from the deleted WorldEntrySyncIconMixin's
    // lazuli$drawSyncIcon -- same 8px-square, colored-fill convention.
    private static final int SYNC_ICON_SIZE = 8;
    private static final int SYNC_ICON_MARGIN = 4;
    private static final int COLOR_SYNC_ENABLED = 0xFF3399FF;
    private static final int COLOR_SYNC_DISABLED = 0xFF808080;

    // cloud-sync-status-ui-simplify FR-2: the one consolidated status
    // square's four state colors -- superseded the deleted four-slot layout
    // (freshness/conflict squares removed, terminal-status/in-progress
    // squares folded into this one). Names/values reused verbatim from the
    // deleted per-state constants to minimize churn (Decision 1).
    private static final int COLOR_STATUS_UNSYNCED = 0xFFCC3333;
    private static final int COLOR_STATUS_SYNCED = 0xFF33CC33;
    private static final int COLOR_STATUS_CONFLICT = 0xFFCC33CC;

    // FR-2/FR-3: Syncing state -- an indeterminate, continuously animating
    // spinner/marquee square (F17: no real progress fraction is available
    // this pass), covering both the upstream (upload) and downstream
    // ("Keep Cloud" restore) sub-cases (FR-3.1/FR-3.2).
    private static final int COLOR_STATUS_SYNCING_BASE = 0xFF3366CC;
    private static final int COLOR_STATUS_SYNCING_HIGHLIGHT = 0xFF66AAFF;
    private static final long IN_PROGRESS_ANIMATION_PERIOD_MS = 800L;

    // FR-E.3/UI: ported from the deleted CloudOnlyWorldListEntry.
    private static final DateTimeFormatter SYNCED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

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
    private static final int COLOR_RESOLVE_CONFLICT = 0xFFAA33CC;

    /**
     * cloud-sync-conflict-ux Decision 3: extended to a 6-element array,
     * appending a third "Resolve Cloud Conflict" pill to the left of Play
     * (only when {@code showResolveButton}) without changing Play/Edit's own
     * bounds for non-conflicted rows.
     */
    private static int[] pillBounds(TextRenderer font, int x, int width, boolean showResolveButton) {
        return pillBounds(font, x, width, showResolveButton, "Play", "Edit");
    }

    /**
     * Bug-fix (cloud-world-entry-parity live-test fixes): the left/right pill
     * label strings are now parameters rather than the hardcoded "Play"/
     * "Edit" -- the cloud-only row's "Download & Play"/"Download" pills are
     * visibly wider than "Play"/"Edit" and must size (and hit-test) to their
     * own real label width, not "Play"/"Edit"'s, or the drawn pill overflows
     * its own text and the clickable area no longer matches what's on screen.
     */
    private static int[] pillBounds(TextRenderer font, int x, int width, boolean showResolveButton,
            String leftLabel, String rightLabel) {
        int playW = font.getWidth(leftLabel) + PILL_PADDING * 2;
        int editW = font.getWidth(rightLabel) + PILL_PADDING * 2;
        int editX = x + width - 8 - editW;
        int playX = editX - PILL_GAP - playW;
        int resolveW = showResolveButton ? font.getWidth("Resolve Cloud Conflict") + PILL_PADDING * 2 : 0;
        int resolveX = showResolveButton ? playX - PILL_GAP - resolveW : 0;
        return new int[] { playX, playW, editX, editW, resolveX, resolveW };
    }

    // cloud-world-entry-parity Requirement 4: the "Download" pill's own color
    // convention, distinct from Play-green/Edit-gray/Resolve-purple.
    private static final int COLOR_DOWNLOAD_ONLY = 0xFF4A6FA5;
    private static final int COLOR_DOWNLOAD_ONLY_HOVER = 0xFF5C84C2;
    private static final String BLOCKED_SYNCING_TOOLTIP = "Cannot play or edit while syncing with Steam Cloud.";
    // Requirement 3a: the cloud badge overlay tint, reusing the color the old
    // flat-square icon-fallback used to use.
    private static final int COLOR_CLOUD_BADGE = 0xFF3399FF;

    /**
     * cloud-world-entry-parity Requirement 1: the small per-row view shared by
     * both a local and a cloud-only row, so {@link #drawRow}/{@link
     * #handleRowClick} never hand-duplicate layout/behavior between the two
     * kinds again. {@code payload} is the {@code WorldListWidget.WorldEntry}
     * or {@code CloudOnlyWorldSummary} this row was built from, used only by
     * the pill-action callbacks.
     */
    private record RowView(String worldSlug, String displayName, String subtitle, Identifier iconId,
            boolean isCloudOnly, Object payload) {
    }

    /** Requirement 1: builds a local row's view from its real {@code LevelSummary}. */
    private RowView forLocal(WorldListWidget.WorldEntry entry) {
        LevelSummary summary = entry.getLevel();
        String subtitle = summary.getGameMode().getTranslatableName().getString() + " · "
                + relativeTime(summary.getLastPlayed());
        Identifier iconId = iconCache.forWorld(summary.getName(), summary.getIconPath());
        return new RowView(summary.getName(), entry.getLevelDisplayName(), subtitle, iconId, false, entry);
    }

    /**
     * Requirement 1/2/3a: builds a cloud-only row's view, reusing the same
     * fields/format a local row's subtitle uses (Requirement 3b's real
     * {@code gameMode}/{@code lastPlayedMillis} when available, falling back
     * to "Unknown"/the synced-at timestamp otherwise -- an old metadata file
     * predating Requirement 3b degrades gracefully per the spec's
     * Compatibility section). Requirement 3a: a decoded real icon when
     * available, else the same vanilla-default fallback a brand-new local
     * world with no {@code icon.png} already shows (a nonexistent icon path
     * under this world's own save folder, exactly like a real world would
     * have before it ever gets a custom icon).
     */
    private RowView forCloudOnly(CloudOnlyWorldSummary cloudOnly) {
        byte[] decodedIconBytes = decodeIconBase64OrNull(cloudOnly.iconBase64());
        Identifier iconId;
        if (decodedIconBytes != null) {
            iconId = iconCache.forServer("cloud:" + cloudOnly.worldSlug(), decodedIconBytes);
        } else {
            iconId = iconCache.forWorld(cloudOnly.worldSlug(),
                    MinecraftClient.getInstance().getLevelStorage().resolve(cloudOnly.worldSlug()).resolve("icon.png"));
        }
        String gameMode = cloudOnly.gameMode() != null ? cloudOnly.gameMode() : "Unknown";
        long lastPlayedMillis = cloudOnly.lastPlayedMillis() >= 0 ? cloudOnly.lastPlayedMillis() : cloudOnly.syncedAtTimestamp();
        String subtitle = gameMode + " · " + relativeTime(lastPlayedMillis);
        return new RowView(cloudOnly.worldSlug(), cloudOnly.displayName(), subtitle, iconId, true, cloudOnly);
    }

    private final MainMenuStateMachine state;
    private final MainMenuScreen owner;
    private final IconTextureCache iconCache = new IconTextureCache(LazuliMod.LOGGER::warn);
    private WorldListWidget dataWidget;
    private List<WorldListWidget.WorldEntry> entries = List.of();
    private List<CloudOnlyWorldSummary> cloudOnlyWorlds = List.of();
    private ButtonWidget createButton;
    private boolean tabActive;

    // FR-P3/Performance: the freshness scan is cached, computed at reload()
    // time and refreshed on upload-completion (detected each render frame
    // as an isUploadInProgress true->false transition) -- never a per-frame
    // Files.walk.
    private final java.util.Map<String, UpToDateStatus> freshnessCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> previouslyInProgress = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // FR-V.2: the two-sided-conflict classification, computed at the same
    // checkpoints as freshnessCache above (reload()/upload-completion) --
    // never a per-frame scan.
    private final java.util.Map<String, ConflictStatus> conflictCache = new java.util.concurrent.ConcurrentHashMap<>();

    // Requirement 4's screen-less "Download" pill has no progress screen to
    // surface a failure on, so it leaves a short-lived, non-blocking status
    // line on this panel instead of failing silently (bug fix: previously
    // only logged via LazuliMod.LOGGER.warn, invisible to the player).
    private volatile String downloadOnlyStatusMessage;
    private volatile long downloadOnlyStatusSetAtMillis;
    private static final long DOWNLOAD_ONLY_STATUS_DURATION_MILLIS = 5000L;

    public WorldsPanel(MainMenuStateMachine state, MainMenuScreen owner) {
        this.state = state;
        this.owner = owner;
        reload();
    }

    /**
     * Package-private so a completed/cancelled {@code WorldRestoreScreen}
     * (FR-E.5) can refresh this tab's list on return.
     *
     * <p><strong>Bug fix (cloud-world-entry-parity live-test fixes, Bug A --
     * "local saved worlds don't appear, only cloud-only worlds do"):</strong>
     * this used to call {@code dataWidget.load()} and then poll {@code
     * dataWidget.children()} for the result. That never worked: {@code
     * WorldListWidget}'s own {@code load()} only stores a {@code
     * CompletableFuture} into a private {@code levelsFuture} field: the
     * future is only ever drained (via the private {@code tryGet()}/{@code
     * show(List)} pair that actually calls {@code addEntry(...)}, populating
     * {@code children()}) from inside {@code WorldListWidget#renderWidget}
     * (confirmed via {@code javap -c}: {@code tryGet()}/{@code show(List)}
     * have exactly one caller each, {@code renderWidget}). This panel's
     * {@code dataWidget} is deliberately headless -- built via {@code
     * WorldListWidget.Builder} but never added to the screen's widget tree,
     * so {@code renderWidget()} is never invoked, so {@code children()}
     * stays permanently empty regardless of how long this panel polls it.
     * (This defect is 1.21.11-specific: fabric-26.1/fabric-26.2 never used
     * {@code WorldListWidget} for this at all, calling {@code
     * LevelStorageSource.findLevelCandidates()}/{@code loadLevelSummaries()}
     * directly instead -- see {@code minecraft.md}'s Known Cross-Version API
     * Differences table.)
     *
     * <p>Fixed by driving the same real, public {@code
     * LevelStorage.getLevelList()}/{@code loadSummaries(LevelList)} future
     * this class's own {@link #launchWorld} already trusts for a single
     * world, then building real {@code WorldListWidget.WorldEntry} rows from
     * its result via the same public 2-arg inner-class constructor {@link
     * #launchWorld} already uses -- {@code dataWidget} now serves purely as
     * that constructor's required outer instance, never as the entry
     * source.
     */
    void reload() {
        iconCache.invalidateAll();
        try {
            dataWidget = new WorldListWidget.Builder(MinecraftClient.getInstance(), owner)
                    .width(320).height(240)
                    .toWidget();
            LevelStorage levelStorage = MinecraftClient.getInstance().getLevelStorage();
            CompletableFuture<List<LevelSummary>> future = levelStorage.loadSummaries(levelStorage.getLevelList());
            future.thenAcceptAsync(summaries -> {
                List<WorldListWidget.WorldEntry> loaded = new ArrayList<>();
                for (LevelSummary summary : summaries) {
                    loaded.add(dataWidget.new WorldEntry(dataWidget, summary));
                }
                this.entries = loaded;
                refreshCloudOnlyWorlds();
                refreshFreshnessCache();
            }, MinecraftClient.getInstance()).exceptionally(e -> {
                LazuliMod.LOGGER.warn("Failed to load saved world list: " + e);
                this.entries = List.of();
                refreshCloudOnlyWorlds();
                return null;
            });
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to load saved world list: " + e);
            this.entries = List.of();
            refreshCloudOnlyWorlds();
        }
    }

    /**
     * FR-P3/Performance: computes and caches each real world's freshness
     * classification once, at reload() time -- not per render frame. A
     * no-op (leaves the cache untouched) if Steam Cloud Sync's freshness
     * hook is unavailable.
     */
    private void refreshFreshnessCache() {
        WorldFreshnessHook hook = WorldFreshnessHookHolder.getOrNull();
        if (hook == null) {
            return;
        }
        for (WorldListWidget.WorldEntry entry : entries) {
            refreshFreshnessFor(hook, entry.getLevel().getName());
        }
    }

    private void refreshFreshnessFor(WorldFreshnessHook hook, String worldSlug) {
        try {
            String worldFolderPath = MinecraftClient.getInstance().getLevelStorage().resolve(worldSlug).toAbsolutePath().toString();
            freshnessCache.put(worldSlug, hook.upToDateStatusFor(worldSlug, worldFolderPath));
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to compute Steam Cloud freshness for world \"" + worldSlug + "\": " + e);
            freshnessCache.put(worldSlug, UpToDateStatus.UNKNOWN);
        }
        refreshConflictFor(worldSlug);
    }

    /**
     * FR-V.2: recomputed at the same checkpoints as {@link #refreshFreshnessFor}
     * (reload()/upload-completion). A no-op (leaves the cache untouched) if
     * Steam Cloud Sync's conflict hook is unavailable.
     */
    private void refreshConflictFor(String worldSlug) {
        WorldConflictHook hook = WorldConflictHookHolder.getOrNull();
        if (hook == null) {
            return;
        }
        try {
            String worldFolderPath = MinecraftClient.getInstance().getLevelStorage().resolve(worldSlug).toAbsolutePath().toString();
            conflictCache.put(worldSlug, hook.checkConflictFor(worldSlug, worldFolderPath));
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to compute Steam Cloud conflict status for world \"" + worldSlug + "\": " + e);
            conflictCache.put(worldSlug, ConflictStatus.NONE);
        }
    }

    /**
     * FR-E.2/FR-E.7: cheap local set-difference against the already-pulled
     * fingerprint cache, no Steam I/O -- refreshed alongside every world-list
     * reload so a just-restored world's synthetic row disappears once it
     * becomes real (FR-E.5).
     */
    private void refreshCloudOnlyWorlds() {
        CloudOnlyWorldsHook hook = CloudOnlyWorldsHookHolder.getOrNull();
        if (hook == null) {
            LazuliMod.LOGGER.warn("refreshCloudOnlyWorlds: CloudOnlyWorldsHook is not published (Steam Cloud Sync not "
                    + "activated for this session) -- cloud-only rows will not render.");
            cloudOnlyWorlds = List.of();
            return;
        }
        List<String> localFolderNames = new ArrayList<>();
        for (WorldListWidget.WorldEntry entry : entries) {
            localFolderNames.add(entry.getLevel().getName());
        }
        try {
            cloudOnlyWorlds = hook.listCloudOnlyWorlds(localFolderNames);
            LazuliMod.LOGGER.info("refreshCloudOnlyWorlds: {} local folder(s) {}, {} cloud-only world(s) found",
                    localFolderNames.size(), localFolderNames, cloudOnlyWorlds.size());
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to list cloud-only worlds: " + e);
            cloudOnlyWorlds = List.of();
        }
    }

    private static String formatSyncedAt(long syncedAtTimestamp) {
        return SYNCED_AT_FORMAT.format(Instant.ofEpochMilli(syncedAtTimestamp));
    }

    /**
     * FR-U's shared tooltip timestamp formatter (Decision 4): kept a small
     * private static helper duplicated per platform module, consistent with
     * {@code WorldsPanel} already being per-module, not shared.
     */
    private static String formatInstant(long epochMillis) {
        return DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMillis));
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

        String downloadStatus = downloadOnlyStatusMessage;
        if (downloadStatus != null) {
            if (System.currentTimeMillis() - downloadOnlyStatusSetAtMillis < DOWNLOAD_ONLY_STATUS_DURATION_MILLIS) {
                context.drawText(font, Text.literal(downloadStatus), leftX, y + 18, 0xFFFF5555, false);
            } else {
                downloadOnlyStatusMessage = null;
            }
        }

        int rowY = y + 30;
        // Bug fix: this used to `return` here on an empty local world list,
        // which also skipped the cloud-only-worlds loop further down --
        // hiding real, successfully-detected cloud-only worlds any time this
        // device had zero local saves (exactly the "different instance, no
        // worlds appear" report this fixes). Only the local-entries loop
        // itself is skipped now; cloud-only rendering always still runs.
        if (entries.isEmpty()) {
            context.drawText(font, Text.literal("No saved worlds yet."), leftX, rowY, 0xFF908C7F, false);
            rowY += 14;
        }

        for (WorldListWidget.WorldEntry entry : entries) {
            RowView view = forLocal(entry);
            int rowHeight = view.worldSlug().equals(state.expandedRowId()) ? ROW_HEIGHT_EXPANDED : ROW_HEIGHT_COMPACT;
            if (rowY + rowHeight > y + height) {
                break;
            }
            boolean expanded = view.worldSlug().equals(state.expandedRowId());
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + rowHeight;
            drawRow(context, font, view, x, leftX, rowY, width, rowHeight, expanded, mouseX, mouseY, hovered);
            rowY += rowHeight + 4;
        }

        for (CloudOnlyWorldSummary cloudOnly : cloudOnlyWorlds) {
            RowView view = forCloudOnly(cloudOnly);
            boolean expanded = view.worldSlug().equals(state.expandedRowId());
            int rowHeight = expanded ? ROW_HEIGHT_EXPANDED : ROW_HEIGHT_COMPACT;
            if (rowY + rowHeight > y + height) {
                break;
            }
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + rowHeight;
            drawRow(context, font, view, x, leftX, rowY, width, rowHeight, expanded, mouseX, mouseY, hovered);
            rowY += rowHeight + 4;
        }
    }

    /**
     * cloud-world-entry-parity Requirement 1: the one shared row-drawing
     * method both a local and a cloud-only row funnel through -- hover fill,
     * icon blit, name/subtitle text, sync icons, and (while expanded) the
     * pill row are all identical statements for both kinds; the only
     * intentional per-kind branches are the cloud badge overlay (Requirement
     * 3a) and which pill set/action is drawn (Requirement 4).
     */
    private void drawRow(DrawContext context, TextRenderer font, RowView view, int x, int leftX, int rowY,
            int width, int rowHeight, boolean expanded, int mouseX, int mouseY, boolean hovered) {
        context.fill(x, rowY, x + width, rowY + rowHeight, hovered ? 0xFF2A2820 : 0xFF201E17);

        int textX;
        if (expanded) {
            // FR-B3.1: expanded row shows the world icon scaled up to fill
            // the larger thumbnail area (single real icon, no repeated tiles).
            int gridSize = rowHeight - IMAGE_MARGIN * 2;
            int gridX = leftX + IMAGE_MARGIN;
            int gridY = rowY + IMAGE_MARGIN;
            context.drawTexture(RenderPipelines.GUI_TEXTURED, view.iconId(), gridX, gridY, 0f, 0f,
                    gridSize, gridSize, ICON_TEX_SIZE, ICON_TEX_SIZE);
            textX = gridX + gridSize + 6;
            if (view.isCloudOnly()) {
                drawCloudBadge(context, gridX, gridY, gridSize);
            }
        } else {
            // FX13.1/FX13.2: real-or-fallback world icon thumbnail, sized to
            // 2/3 of the row height (full-height read as oversized against
            // the text), 1:1, no border, vertically centered.
            int iconSize = (rowHeight - IMAGE_MARGIN * 2) * 2 / 3;
            int iconX = leftX + IMAGE_MARGIN;
            int iconY = rowY + (rowHeight - iconSize) / 2;
            context.drawTexture(RenderPipelines.GUI_TEXTURED, view.iconId(), iconX, iconY, 0f, 0f,
                    iconSize, iconSize, ICON_TEX_SIZE, ICON_TEX_SIZE);
            textX = iconX + iconSize + 6;
            if (view.isCloudOnly()) {
                drawCloudBadge(context, iconX, iconY, iconSize);
            }
        }

        context.drawText(font, Text.literal(view.displayName()), textX, rowY + 4, 0xFFEAE8E1, false);
        context.drawText(font, Text.literal(view.subtitle()), textX, rowY + 15, 0xFF908C7F, false);

        if (view.isCloudOnly()) {
            drawCloudOnlySyncIcons(context, view.worldSlug(), x, rowY, width, mouseX, mouseY, hovered);
        } else {
            drawSyncIcons(context, ((WorldListWidget.WorldEntry) view.payload()).getLevel(), x, rowY, width, mouseX, mouseY, hovered);
        }

        if (expanded) {
            int buttonY = rowY + rowHeight - 22;
            if (view.isCloudOnly()) {
                // Requirement 4: the demoted device-label/synced-at line,
                // shown only in the expanded body (real data is never
                // discarded, just moved out of the shared subtitle format).
                CloudOnlyWorldSummary cloudOnly = (CloudOnlyWorldSummary) view.payload();
                String detail = cloudOnly.deviceLabel() + " · Synced " + formatSyncedAt(cloudOnly.syncedAtTimestamp());
                context.drawText(font, Text.literal(detail), textX, rowY + 26, 0xFF908C7F, false);
                drawCloudOnlyPills(context, font, cloudOnly, x, width, buttonY, mouseX, mouseY);
            } else {
                drawLocalPills(context, font, (WorldListWidget.WorldEntry) view.payload(), x, width, buttonY, mouseX, mouseY);
            }
        }
    }

    /** Requirement 3a: a small cloud-tinted badge overlaid at the icon's bottom-right corner, always shown for a cloud-only row. */
    private void drawCloudBadge(DrawContext context, int iconX, int iconY, int iconSize) {
        int badgeSize = SYNC_ICON_SIZE;
        int badgeX = iconX + iconSize - badgeSize;
        int badgeY = iconY + iconSize - badgeSize;
        context.fill(badgeX, badgeY, badgeX + badgeSize, badgeY + badgeSize, COLOR_CLOUD_BADGE);
    }

    /** The exact Play/Edit/Resolve pill drawing a local row has always used, unchanged, extracted verbatim for Requirement 1's shared {@link #drawRow}. */
    private void drawLocalPills(DrawContext context, TextRenderer font, WorldListWidget.WorldEntry entry,
            int x, int width, int buttonY, int mouseX, int mouseY) {
        LevelSummary summary = entry.getLevel();
        String worldSlug = summary.getName();
        boolean isConflicted = isRowConflicted(worldSlug);
        boolean syncEnabled = isSyncEnabledFor(worldSlug);
        UpToDateStatus freshness = freshnessCache.getOrDefault(worldSlug, UpToDateStatus.UNKNOWN);
        boolean isStale = syncEnabled && freshness == UpToDateStatus.STALE;
        boolean isUnknown = syncEnabled && freshness == UpToDateStatus.UNKNOWN;
        boolean isCheckingConflict = isRowCheckingConflict(worldSlug);
        boolean showResolveButton = computeShowResolveButton(isConflicted, syncEnabled, freshness);
        int[] bounds = pillBounds(font, x, width, showResolveButton);
        int playX = bounds[0], playW = bounds[1], editX = bounds[2], editW = bounds[3];
        int resolveX = bounds[4], resolveW = bounds[5];
        boolean playHover = mouseX >= playX && mouseX <= playX + playW && mouseY >= buttonY && mouseY <= buttonY + 18;
        boolean editHover = mouseX >= editX && mouseX <= editX + editW && mouseY >= buttonY && mouseY <= buttonY + 18;
        boolean rowSyncing = isRowSyncing(worldSlug);
        boolean blocked = computeBlocked(rowSyncing, isConflicted, isCheckingConflict, syncEnabled, freshness);
        if (blocked) {
            context.fill(playX, buttonY, playX + playW, buttonY + 18, 0xFF4A4A4A);
            context.drawCenteredTextWithShadow(font, "Play", playX + playW / 2, buttonY + 5, 0xFF908C7F);
            context.fill(editX, buttonY, editX + editW, buttonY + 18, 0xFF4A4A4A);
            context.drawCenteredTextWithShadow(font, "Edit", editX + editW / 2, buttonY + 5, 0xFF908C7F);
            if (playHover || editHover) {
                context.drawTooltip(Text.literal(
                        blockedTooltipFor(worldSlug, isConflicted, isCheckingConflict, isUnknown, isStale)), mouseX, mouseY);
            }
        } else {
            context.fill(playX, buttonY, playX + playW, buttonY + 18, playHover ? 0xFF64A066 : 0xFF528A54);
            context.drawCenteredTextWithShadow(font, "Play", playX + playW / 2, buttonY + 5, 0xFFFFFFFF);
            context.fill(editX, buttonY, editX + editW, buttonY + 18, editHover ? 0xFF3A3A3A : 0xFF2E2E2E);
            context.drawCenteredTextWithShadow(font, "Edit", editX + editW / 2, buttonY + 5, 0xFFFFFFFF);
        }
        if (showResolveButton) {
            boolean resolveHover = mouseX >= resolveX && mouseX <= resolveX + resolveW
                    && mouseY >= buttonY && mouseY <= buttonY + 18;
            context.fill(resolveX, buttonY, resolveX + resolveW, buttonY + 18,
                    resolveHover ? 0xFFBB55DD : COLOR_RESOLVE_CONFLICT);
            context.drawCenteredTextWithShadow(font, "Resolve Cloud Conflict",
                    resolveX + resolveW / 2, buttonY + 5, 0xFFFFFFFF);
        }
    }

    /**
     * Requirement 4: the two-pill "Download & Play"/"Download" row, sharing
     * {@link #pillBounds}'s Play/Edit slot geometry verbatim (never a resolve
     * pill), both muted with the existing blocked tooltip while a download
     * for this slug is already in progress.
     */
    private void drawCloudOnlyPills(DrawContext context, TextRenderer font, CloudOnlyWorldSummary cloudOnly,
            int x, int width, int buttonY, int mouseX, int mouseY) {
        String worldSlug = cloudOnly.worldSlug();
        int[] bounds = pillBounds(font, x, width, false, "Download & Play", "Download");
        int playX = bounds[0], playW = bounds[1], editX = bounds[2], editW = bounds[3];
        boolean playHover = mouseX >= playX && mouseX <= playX + playW && mouseY >= buttonY && mouseY <= buttonY + 18;
        boolean editHover = mouseX >= editX && mouseX <= editX + editW && mouseY >= buttonY && mouseY <= buttonY + 18;
        WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
        boolean blocked = statusHook != null && statusHook.isDownloadInProgress(worldSlug);
        if (blocked) {
            context.fill(playX, buttonY, playX + playW, buttonY + 18, 0xFF4A4A4A);
            context.drawCenteredTextWithShadow(font, "Download & Play", playX + playW / 2, buttonY + 5, 0xFF908C7F);
            context.fill(editX, buttonY, editX + editW, buttonY + 18, 0xFF4A4A4A);
            context.drawCenteredTextWithShadow(font, "Download", editX + editW / 2, buttonY + 5, 0xFF908C7F);
            if (playHover || editHover) {
                context.drawTooltip(Text.literal(BLOCKED_SYNCING_TOOLTIP), mouseX, mouseY);
            }
        } else {
            context.fill(playX, buttonY, playX + playW, buttonY + 18, playHover ? 0xFF64A066 : 0xFF528A54);
            context.drawCenteredTextWithShadow(font, "Download & Play", playX + playW / 2, buttonY + 5, 0xFFFFFFFF);
            context.fill(editX, buttonY, editX + editW, buttonY + 18, editHover ? COLOR_DOWNLOAD_ONLY_HOVER : COLOR_DOWNLOAD_ONLY);
            context.drawCenteredTextWithShadow(font, "Download", editX + editW / 2, buttonY + 5, 0xFFFFFFFF);
        }
    }

    /**
     * cloud-world-entry-parity Requirement 2: the cloud-only-row variant of
     * {@link #drawSyncIcons} -- the toggle square is always disabled/
     * non-interactive, the status square only appears while this world's
     * download is in progress.
     */
    private void drawCloudOnlySyncIcons(DrawContext context, String worldSlug, int rowX, int rowY, int rowWidth,
            int mouseX, int mouseY, boolean rowHovered) {
        int left = syncIconLeft(rowX, rowWidth);
        int top = rowY + SYNC_ICON_MARGIN;
        context.fill(left, top, left + SYNC_ICON_SIZE, top + SYNC_ICON_SIZE, COLOR_SYNC_DISABLED);
        if (rowHovered && mouseX >= left && mouseX < left + SYNC_ICON_SIZE && mouseY >= top && mouseY < top + SYNC_ICON_SIZE) {
            context.drawTooltip(Text.literal("This world has not been downloaded yet."), mouseX, mouseY);
        }

        WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
        if (statusHook == null || !statusHook.isDownloadInProgress(worldSlug)) {
            return;
        }
        int statusLeft = left - SYNC_ICON_MARGIN - SYNC_ICON_SIZE;
        long phase = System.currentTimeMillis() % IN_PROGRESS_ANIMATION_PERIOD_MS;
        int color = phase < IN_PROGRESS_ANIMATION_PERIOD_MS / 2 ? COLOR_STATUS_SYNCING_HIGHLIGHT : COLOR_STATUS_SYNCING_BASE;
        context.fill(statusLeft, top, statusLeft + SYNC_ICON_SIZE, top + SYNC_ICON_SIZE, color);
        if (rowHovered && mouseX >= statusLeft && mouseX < statusLeft + SYNC_ICON_SIZE && mouseY >= top && mouseY < top + SYNC_ICON_SIZE) {
            context.drawTooltip(Text.literal("Downloading from Steam Cloud..."), mouseX, mouseY);
        }
    }

    /**
     * cloud-world-metadata-file spec's cloud-only-worlds-list icon: decodes
     * {@code iconBase64} defensively -- a malformed Base64 string (or no
     * icon at all, {@code null}) must never throw on the render thread, and
     * degrades to the caller's own flat-square fallback instead.
     */
    private static byte[] decodeIconBase64OrNull(String iconBase64) {
        if (iconBase64 == null || iconBase64.isEmpty()) {
            return null;
        }
        try {
            return java.util.Base64.getDecoder().decode(iconBase64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * cloud-sync-status-ui-simplify FR-1's four consolidated states, replacing
     * the deleted four-slot layout's separate freshness/conflict/terminal-
     * status/in-progress squares with exactly one.
     */
    enum ConsolidatedStatus { UNSYNCED, SYNCING, SYNCED, CONFLICT }

    /**
     * cloud-sync-status-ui-simplify FR-1: the precedence-ordered (Conflict
     * &gt; Syncing &gt; Synced &gt; Unsynced), pure computation backing the
     * one consolidated status square -- a plain function of five
     * primitive/enum arguments (no hook lookups, no I/O), package-private
     * static so {@code WorldsPanelStatusTest} can call it directly.
     */
    static ConsolidatedStatus computeConsolidatedStatus(
            ConflictStatus conflict, boolean uploadInProgress, boolean downloadInProgress,
            boolean syncEnabled, UpToDateStatus freshness) {
        if (conflict == ConflictStatus.CONFLICT) {
            return ConsolidatedStatus.CONFLICT;
        }
        if (uploadInProgress || downloadInProgress) {
            return ConsolidatedStatus.SYNCING;
        }
        if (syncEnabled && freshness == UpToDateStatus.UP_TO_DATE) {
            return ConsolidatedStatus.SYNCED;
        }
        return ConsolidatedStatus.UNSYNCED;
    }

    /**
     * sync-conflict-coverage-gaps Gaps 1-3: the precedence-free (order does
     * not matter -- OR of independent conditions), pure computation backing
     * the Play/Edit blocked gate, package-private static so {@code
     * WorldsPanelStatusTest} can call it directly, mirroring {@link
     * #computeConsolidatedStatus}'s own testability precedent. {@code
     * rowSyncing} is the caller's already-computed {@code isRowSyncing(...)}
     * (upload or download in progress); {@code syncEnabled}-gated STALE/
     * UNKNOWN mirrors Gaps 1/3's acceptance criteria exactly.
     */
    static boolean computeBlocked(boolean rowSyncing, boolean isConflicted, boolean checkingConflict,
            boolean syncEnabled, UpToDateStatus freshness) {
        return rowSyncing || isConflicted || checkingConflict
                || (syncEnabled && freshness == UpToDateStatus.STALE)
                || (syncEnabled && freshness == UpToDateStatus.UNKNOWN);
    }

    /**
     * Gap 3: the resolve pill's own visibility condition, deliberately kept
     * independent of {@link #computeConsolidatedStatus}/{@link
     * #computeBlocked} (it answers a different question -- "is there
     * cloud-resolution action available" -- per the spec's precedence design
     * decision).
     */
    static boolean computeShowResolveButton(boolean isConflicted, boolean syncEnabled, UpToDateStatus freshness) {
        return isConflicted || (syncEnabled && freshness == UpToDateStatus.STALE);
    }

    /**
     * Request 2 (cloud-sync-threshold-and-full-sync-only): the consolidated
     * status square hides when sync is off for this world, EXCEPT when there is
     * still an unresolved conflict left over from before sync was disabled --
     * mirrors {@link #computeShowResolveButton}'s own isConflicted-first shape
     * so a hidden square and a hidden resolve pill never independently disagree
     * about whether a conflict is "live."
     */
    static boolean computeShowStatusIndicator(boolean syncEnabled, boolean isConflicted) {
        return syncEnabled || isConflicted;
    }

    /**
     * FR-A.1/FR-A.3 (ported verbatim, colors/tooltips, from the deleted
     * {@code WorldEntrySyncIconMixin.lazuli$drawSyncIcon}), simplified per
     * cloud-sync-status-ui-simplify FR-2/FR-6: exactly two slots at the row's
     * right edge, left to right -- the one consolidated status square (new),
     * then the existing toggle icon (unchanged position/semantics). No icon
     * at all if Steam Cloud Sync is unavailable (FR-A.6).
     */
    private void drawSyncIcons(DrawContext context, LevelSummary summary,
            int rowX, int rowY, int rowWidth, int mouseX, int mouseY, boolean rowHovered) {
        WorldSyncToggleHook hook = WorldSyncToggleHookHolder.getOrNull();
        if (hook == null) {
            return;
        }
        String worldSlug = summary.getName();
        boolean enabled = hook.isSyncEnabled(worldSlug);
        int left = syncIconLeft(rowX, rowWidth);
        int top = rowY + SYNC_ICON_MARGIN;
        context.fill(left, top, left + SYNC_ICON_SIZE, top + SYNC_ICON_SIZE, enabled ? COLOR_SYNC_ENABLED : COLOR_SYNC_DISABLED);

        // FR-5: the toggle square's own enabled/disabled tooltip (previously absent).
        if (rowHovered && mouseX >= left && mouseX < left + SYNC_ICON_SIZE && mouseY >= top && mouseY < top + SYNC_ICON_SIZE) {
            context.drawTooltip(Text.literal(enabled
                    ? "Steam Cloud sync is ON for this world. Click to turn off."
                    : "Steam Cloud sync is OFF for this world. Click to turn on."), mouseX, mouseY);
        }

        WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
        if (statusHook == null) {
            return;
        }
        int statusLeft = left - SYNC_ICON_MARGIN - SYNC_ICON_SIZE;

        boolean uploadInProgress = statusHook.isUploadInProgress(worldSlug);
        // FR-P3/Performance: an isUploadInProgress true->false transition,
        // detected here (polled once per render frame, same as the flag
        // itself), is one of the two "recompute freshness" checkpoints --
        // the other being reload() itself.
        if (!uploadInProgress && previouslyInProgress.remove(worldSlug)) {
            WorldFreshnessHook freshnessHook = WorldFreshnessHookHolder.getOrNull();
            if (freshnessHook != null) {
                refreshFreshnessFor(freshnessHook, worldSlug);
            }
        } else if (uploadInProgress) {
            previouslyInProgress.add(worldSlug);
        }

        if (computeShowStatusIndicator(enabled, isRowConflicted(worldSlug))) {
            drawConsolidatedStatusIndicator(context, statusHook, worldSlug, enabled, uploadInProgress, statusLeft, top, mouseX, mouseY, rowHovered);
        }
    }

    /**
     * cloud-sync-status-ui-simplify FR-2: the single consolidated status
     * square, replacing the deleted {@code drawTerminalStatusIcon}/{@code
     * drawInProgressIndicator}/{@code drawFreshnessIndicator}/{@code
     * drawConflictIndicator}. Clickable only in the Conflict state (opens
     * {@code WorldConflictScreen}, mirrored by {@link #mouseClicked}'s
     * hit-test for this same slot).
     */
    private void drawConsolidatedStatusIndicator(DrawContext context, WorldSyncStatusHook statusHook,
            String worldSlug, boolean syncEnabled, boolean uploadInProgress, int left, int top, int mouseX, int mouseY, boolean rowHovered) {
        boolean downloadInProgress = statusHook.isDownloadInProgress(worldSlug);
        ConflictStatus conflict = conflictCache.getOrDefault(worldSlug, ConflictStatus.NONE);
        UpToDateStatus freshness = freshnessCache.getOrDefault(worldSlug, UpToDateStatus.UNKNOWN);
        ConsolidatedStatus consolidated = computeConsolidatedStatus(conflict, uploadInProgress, downloadInProgress, syncEnabled, freshness);

        int color = switch (consolidated) {
            case UNSYNCED -> COLOR_STATUS_UNSYNCED;
            case SYNCING -> {
                long phase = System.currentTimeMillis() % IN_PROGRESS_ANIMATION_PERIOD_MS;
                yield phase < IN_PROGRESS_ANIMATION_PERIOD_MS / 2 ? COLOR_STATUS_SYNCING_HIGHLIGHT : COLOR_STATUS_SYNCING_BASE;
            }
            case SYNCED -> COLOR_STATUS_SYNCED;
            case CONFLICT -> COLOR_STATUS_CONFLICT;
        };
        context.fill(left, top, left + SYNC_ICON_SIZE, top + SYNC_ICON_SIZE, color);

        if (rowHovered && mouseX >= left && mouseX < left + SYNC_ICON_SIZE && mouseY >= top && mouseY < top + SYNC_ICON_SIZE) {
            context.drawTooltip(Text.literal(
                    consolidatedStatusTooltip(consolidated, statusHook, worldSlug, syncEnabled, uploadInProgress, downloadInProgress, freshness)),
                    mouseX, mouseY);
        }
    }

    /** FR-5: one tooltip string per {@link ConsolidatedStatus} state. */
    private String consolidatedStatusTooltip(ConsolidatedStatus consolidated, WorldSyncStatusHook statusHook, String worldSlug,
            boolean syncEnabled, boolean uploadInProgress, boolean downloadInProgress, UpToDateStatus freshness) {
        return switch (consolidated) {
            case CONFLICT -> "This world changed on both this device and another device -- click to resolve.";
            case SYNCING -> uploadInProgress ? "Uploading to Steam Cloud..." : "Downloading from Steam Cloud...";
            case SYNCED -> syncedTooltipFor(worldSlug);
            case UNSYNCED -> unsyncedTooltipFor(statusHook, worldSlug, syncEnabled, freshness);
        };
    }

    /**
     * FR-5's Unsynced base text, extended with the most specific known
     * reason available, in priority order: {@code SYNC_ERROR} detail,
     * {@code SKIPPED_TOO_LARGE}, sync disabled, {@code STALE} (reusing
     * {@link #freshnessTooltipFor}'s existing detail), else {@code UNKNOWN}.
     */
    private String unsyncedTooltipFor(WorldSyncStatusHook statusHook, String worldSlug, boolean syncEnabled, UpToDateStatus freshness) {
        String base = "This world is not synced with Steam Cloud.";
        SyncStatus status = statusHook.statusFor(worldSlug);
        if (status == SyncStatus.SYNC_ERROR) {
            String error = statusHook.lastErrorFor(worldSlug);
            return base + " " + (error != null ? error : "Steam Cloud sync failed.");
        }
        if (status == SyncStatus.SKIPPED_TOO_LARGE) {
            return base + " (too large to sync automatically)";
        }
        if (!syncEnabled) {
            return base + " (sync is turned off for this world)";
        }
        if (freshness == UpToDateStatus.STALE) {
            WorldFreshnessHook hook = WorldFreshnessHookHolder.getOrNull();
            return hook != null ? freshnessTooltipFor(hook, worldSlug, freshness) : base;
        }
        return base + " (has not been synced yet)";
    }

    /**
     * FR-U.3's {@code SYNCED} tooltip (F18 gap): reuses the existing
     * fingerprint's {@code syncedAtTimestamp} via {@link WorldFreshnessHook}'s
     * FR-U.1 detail accessor -- no new tracker state needed (F19).
     */
    private String syncedTooltipFor(String worldSlug) {
        WorldFreshnessHook hook = WorldFreshnessHookHolder.getOrNull();
        if (hook == null) {
            return "Synced to Steam Cloud.";
        }
        try {
            String worldFolderPath = MinecraftClient.getInstance().getLevelStorage().resolve(worldSlug).toAbsolutePath().toString();
            FreshnessDetail detail = hook.upToDateStatusDetailFor(worldSlug, worldFolderPath);
            long syncedAt = detail.status() == UpToDateStatus.UP_TO_DATE ? detail.ownSyncedAtTimestamp() : -1L;
            return syncedAt >= 0
                    ? "Synced to Steam Cloud at " + formatInstant(syncedAt) + "."
                    : "Synced to Steam Cloud.";
        } catch (Exception e) {
            return "Synced to Steam Cloud.";
        }
    }

    /**
     * FR-U.1: the richest detail available for the current concrete state,
     * only computed on hover (FR-U.4) -- reads {@link WorldFreshnessHook}'s
     * FR-U.1 detail accessor rather than the bare {@code UpToDateStatus}.
     */
    private String freshnessTooltipFor(WorldFreshnessHook hook, String worldSlug, UpToDateStatus status) {
        try {
            String worldFolderPath = MinecraftClient.getInstance().getLevelStorage().resolve(worldSlug).toAbsolutePath().toString();
            FreshnessDetail detail = hook.upToDateStatusDetailFor(worldSlug, worldFolderPath);
            return switch (detail.status()) {
                case UP_TO_DATE -> detail.ownSyncedAtTimestamp() >= 0
                        ? "Local world matches the last Steam Cloud sync from this device (synced "
                                + formatInstant(detail.ownSyncedAtTimestamp()) + ")."
                        : "Local world matches the last Steam Cloud sync from this device.";
                case STALE -> detail.otherDeviceLabel() != null
                        ? "Last synced from \"" + detail.otherDeviceLabel() + "\" at "
                                + formatInstant(detail.otherDeviceSyncedAtTimestamp()) + "; local copy may be out of date."
                        : detail.ownSyncedAtTimestamp() >= 0 && detail.localLastModifiedMillis() >= 0
                                ? "This world changed locally after it was last synced (local change: "
                                        + formatInstant(detail.localLastModifiedMillis()) + "; last synced: "
                                        + formatInstant(detail.ownSyncedAtTimestamp()) + ")."
                                : "This world has changed since it was last synced to Steam Cloud.";
                case UNKNOWN -> "Sync status unknown -- this world has not been synced yet, or sync status has not loaded.";
            };
        } catch (Exception e) {
            return switch (status) {
                case UP_TO_DATE -> "Local world matches the last Steam Cloud sync from this device.";
                case STALE -> "This world has changed since it was last synced to Steam Cloud.";
                case UNKNOWN -> "Sync status unknown -- this world has not been synced yet, or sync status has not loaded.";
            };
        }
    }

    private static int syncIconLeft(int rowX, int rowWidth) {
        return rowX + rowWidth - SYNC_ICON_MARGIN - SYNC_ICON_SIZE;
    }

    /**
     * cloud-sync-status-ui-simplify FR-4: {@code true} while this world's
     * consolidated status is Syncing (upload or "Keep Cloud" download in
     * progress) -- gates both Play and Edit identically in both
     * {@link #render} and {@link #mouseClicked}, computed once per row to
     * avoid the two call sites' guard conditions drifting apart.
     */
    private boolean isRowSyncing(String worldSlug) {
        WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
        if (statusHook == null) {
            return false;
        }
        return statusHook.isUploadInProgress(worldSlug) || statusHook.isDownloadInProgress(worldSlug);
    }

    /**
     * cloud-sync-conflict-ux FR-2.5: the resolve pill (and the FR-1 Conflict
     * tooltip precedence) must not consider a world Conflicted while its
     * Keep-Cloud restore is actively downloading -- {@code conflictCache} is
     * only cleared on restore *completion*
     * ({@code WorldConflictResolutionHook#recordKeepCloudResolution}), so an
     * explicit {@code !isDownloadInProgress} guard is required here to avoid
     * a stale-Conflict resolve pill/tooltip during that window.
     */
    private boolean isRowConflicted(String worldSlug) {
        if (conflictCache.getOrDefault(worldSlug, ConflictStatus.NONE) != ConflictStatus.CONFLICT) {
            return false;
        }
        WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
        return statusHook == null || !statusHook.isDownloadInProgress(worldSlug);
    }

    /**
     * sync-conflict-coverage-gaps Gap 2: {@code true} while an async
     * toggle-on {@code checkConflictFor} is in flight for this world --
     * blocks Play/Edit exactly like {@link #isRowSyncing(String)}.
     */
    private boolean isRowCheckingConflict(String worldSlug) {
        WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
        return statusHook != null && statusHook.isConflictCheckPending(worldSlug);
    }

    /** sync-conflict-coverage-gaps Gaps 1/3: whether sync is enabled for this world. */
    private boolean isSyncEnabledFor(String worldSlug) {
        WorldSyncToggleHook toggleHook = WorldSyncToggleHookHolder.getOrNull();
        return toggleHook != null && toggleHook.isSyncEnabled(worldSlug);
    }

    /**
     * sync-conflict-coverage-gaps Gaps 1-3: the blocked-Play/Edit tooltip,
     * precedence Conflict &gt; Checking &gt; Syncing (checked by the caller
     * before falling into this method) &gt; Stale &gt; Unknown -- reusing
     * existing copy verbatim per the spec's decided tooltip reuse (no new
     * strings invented for Gaps 1/3).
     */
    private String blockedTooltipFor(String worldSlug, boolean isConflicted, boolean isCheckingConflict, boolean isUnknown, boolean isStale) {
        if (isConflicted) {
            return "Cannot play or edit while this world has an unresolved Steam Cloud conflict. Resolve it first.";
        }
        if (isCheckingConflict) {
            return "Checking Steam Cloud sync status...";
        }
        if (isRowSyncing(worldSlug)) {
            return "Cannot play or edit while syncing with Steam Cloud.";
        }
        WorldFreshnessHook hook = WorldFreshnessHookHolder.getOrNull();
        if (isStale) {
            return hook != null ? freshnessTooltipFor(hook, worldSlug, UpToDateStatus.STALE)
                    : "This world has changed since it was last synced to Steam Cloud.";
        }
        if (isUnknown) {
            return hook != null ? freshnessTooltipFor(hook, worldSlug, UpToDateStatus.UNKNOWN)
                    : "Sync status unknown -- this world has not been synced yet, or sync status has not loaded.";
        }
        return "Cannot play or edit while syncing with Steam Cloud.";
    }

    /** @return true if this click was consumed by a row/button in this panel. */
    public boolean mouseClicked(int x, int y, int width, int height, double mouseX, double mouseY) {
        int rowY = y + 30;
        for (WorldListWidget.WorldEntry entry : entries) {
            RowView view = forLocal(entry);
            boolean expanded = view.worldSlug().equals(state.expandedRowId());
            int rowHeight = expanded ? ROW_HEIGHT_EXPANDED : ROW_HEIGHT_COMPACT;
            if (rowY + rowHeight > y + height) {
                break;
            }
            if (handleRowClick(view, expanded, x, width, rowY, rowHeight, mouseX, mouseY)) {
                return true;
            }
            rowY += rowHeight + 4;
        }

        for (CloudOnlyWorldSummary cloudOnly : cloudOnlyWorlds) {
            RowView view = forCloudOnly(cloudOnly);
            boolean expanded = view.worldSlug().equals(state.expandedRowId());
            int rowHeight = expanded ? ROW_HEIGHT_EXPANDED : ROW_HEIGHT_COMPACT;
            if (rowY + rowHeight > y + height) {
                break;
            }
            if (handleRowClick(view, expanded, x, width, rowY, rowHeight, mouseX, mouseY)) {
                return true;
            }
            rowY += rowHeight + 4;
        }
        return false;
    }

    /**
     * cloud-world-entry-parity Requirement 1: the one shared hit-test method
     * both a local and a cloud-only row funnel through, mirroring {@link
     * #drawRow}'s own branches -- the upload-in-progress whole-row block and
     * the toggle/status-square hit-tests only apply to a local row
     * (Requirement 2: neither square is interactive for a cloud-only row);
     * the pill hit-test and the whole-row expand/collapse fallthrough apply
     * to both.
     */
    private boolean handleRowClick(RowView view, boolean expanded, int x, int width, int rowY, int rowHeight,
            double mouseX, double mouseY) {
        if (!view.isCloudOnly()) {
            WorldSyncStatusHook statusHookForBlocking = WorldSyncStatusHookHolder.getOrNull();
            if (statusHookForBlocking != null && statusHookForBlocking.isUploadInProgress(view.worldSlug())
                    && mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + rowHeight) {
                return true;
            }
        }
        if (expanded) {
            int buttonY = rowY + rowHeight - 22;
            if (view.isCloudOnly()) {
                if (handleCloudOnlyPillClick((CloudOnlyWorldSummary) view.payload(), x, width, buttonY, mouseX, mouseY)) {
                    return true;
                }
            } else {
                if (handleLocalPillClick((WorldListWidget.WorldEntry) view.payload(), x, width, buttonY, mouseX, mouseY)) {
                    return true;
                }
            }
        }
        if (!view.isCloudOnly() && WorldSyncToggleHookHolder.getOrNull() != null) {
            String worldSlug = view.worldSlug();
            int left = syncIconLeft(x, width);
            int top = rowY + SYNC_ICON_MARGIN;
            if (mouseX >= left && mouseX < left + SYNC_ICON_SIZE && mouseY >= top && mouseY < top + SYNC_ICON_SIZE) {
                WorldSyncToggleHookHolder.getOrNull().toggleSync(worldSlug);
                return true;
            }
            int statusLeft = left - SYNC_ICON_MARGIN - SYNC_ICON_SIZE;
            if (mouseX >= statusLeft && mouseX < statusLeft + SYNC_ICON_SIZE && mouseY >= top && mouseY < top + SYNC_ICON_SIZE
                    && computeShowStatusIndicator(isSyncEnabledFor(worldSlug), isRowConflicted(worldSlug))
                    && conflictCache.getOrDefault(worldSlug, ConflictStatus.NONE) == ConflictStatus.CONFLICT) {
                openConflictScreen(((WorldListWidget.WorldEntry) view.payload()).getLevel());
                return true;
            }
        }
        if (mouseX >= x && mouseX <= x + width && mouseY >= rowY && mouseY <= rowY + rowHeight) {
            state.toggleRowExpanded(view.worldSlug());
            return true;
        }
        return false;
    }

    /** The exact Play/Edit/Resolve hit-test a local row has always used, unchanged, extracted verbatim for Requirement 1's shared {@link #handleRowClick}. */
    private boolean handleLocalPillClick(WorldListWidget.WorldEntry entry, int x, int width, int buttonY, double mouseX, double mouseY) {
        LevelSummary summary = entry.getLevel();
        String worldSlug = summary.getName();
        boolean isConflicted = isRowConflicted(worldSlug);
        boolean syncEnabled = isSyncEnabledFor(worldSlug);
        UpToDateStatus freshness = freshnessCache.getOrDefault(worldSlug, UpToDateStatus.UNKNOWN);
        boolean showResolveButton = computeShowResolveButton(isConflicted, syncEnabled, freshness);
        int[] bounds = pillBounds(MinecraftClient.getInstance().textRenderer, x, width, showResolveButton);
        int playX = bounds[0], playW = bounds[1], editX = bounds[2], editW = bounds[3];
        int resolveX = bounds[4], resolveW = bounds[5];
        boolean blocked = computeBlocked(isRowSyncing(worldSlug), isConflicted, isRowCheckingConflict(worldSlug), syncEnabled, freshness);
        if (mouseX >= playX && mouseX <= playX + playW && mouseY >= buttonY && mouseY <= buttonY + 18) {
            if (!blocked) {
                MainMenuScreen.playClickSound();
                playWorld(entry);
            }
            return true;
        }
        if (mouseX >= editX && mouseX <= editX + editW && mouseY >= buttonY && mouseY <= buttonY + 18) {
            if (!blocked) {
                MainMenuScreen.playClickSound();
                editWorld(entry);
            }
            return true;
        }
        if (showResolveButton && mouseX >= resolveX && mouseX <= resolveX + resolveW
                && mouseY >= buttonY && mouseY <= buttonY + 18) {
            openConflictScreen(summary);
            return true;
        }
        return false;
    }

    /** Requirement 4: the "Download & Play"/"Download" pill hit-test -- never Edit/Resolve, both blocked identically while a download for this slug is already running. */
    private boolean handleCloudOnlyPillClick(CloudOnlyWorldSummary cloudOnly, int x, int width, int buttonY, double mouseX, double mouseY) {
        int[] bounds = pillBounds(MinecraftClient.getInstance().textRenderer, x, width, false, "Download & Play", "Download");
        int playX = bounds[0], playW = bounds[1], editX = bounds[2], editW = bounds[3];
        WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
        boolean blocked = statusHook != null && statusHook.isDownloadInProgress(cloudOnly.worldSlug());
        if (mouseX >= playX && mouseX <= playX + playW && mouseY >= buttonY && mouseY <= buttonY + 18) {
            if (!blocked) {
                downloadAndPlay(cloudOnly);
            }
            return true;
        }
        if (mouseX >= editX && mouseX <= editX + editW && mouseY >= buttonY && mouseY <= buttonY + 18) {
            if (!blocked) {
                downloadOnly(cloudOnly);
            }
            return true;
        }
        return false;
    }

    /**
     * Requirement 4's "Download & Play" pill: identical to the download
     * feature's own {@code WorldRestoreScreen} flow, except the natural-
     * completion callback additionally launches the just-restored world
     * (never on Cancel, which keeps returning to the Worlds tab while the
     * download continues in the background, unchanged from today).
     */
    private void downloadAndPlay(CloudOnlyWorldSummary cloudOnly) {
        WorldRestoreHook restoreHook = WorldRestoreHookHolder.getOrNull();
        if (restoreHook == null) {
            return;
        }
        WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
        MainMenuScreen.playClickSound();
        MinecraftClient.getInstance().setScreen(new WorldRestoreScreen(cloudOnly, restoreHook, statusHook,
                () -> launchWorld(cloudOnly.worldSlug()),
                () -> {
                    reload();
                    MinecraftClient.getInstance().setScreen(owner);
                }));
    }

    /**
     * Requirement 4's "Download" pill: starts the identical background
     * restore {@code WorldRestoreScreen} would, but never opens that screen
     * -- the player stays on the Worlds tab exactly as if they had opened
     * the progress screen and immediately pressed Cancel, and the world is
     * never launched by this pill.
     */
    private void downloadOnly(CloudOnlyWorldSummary cloudOnly) {
        WorldRestoreHook restoreHook = WorldRestoreHookHolder.getOrNull();
        if (restoreHook == null) {
            return;
        }
        WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
        String worldSlug = cloudOnly.worldSlug();
        if (statusHook != null) {
            statusHook.markDownloadPending(worldSlug);
        }
        MainMenuScreen.playClickSound();
        restoreHook.beginRestore(worldSlug, new de.lazuli.api.cloudsync.RestoreProgressListener() {
            @Override
            public void onProgress(de.lazuli.api.cloudsync.RestoreProgress progress) {
                // no-op, no screen watching this download's progress
            }

            @Override
            public void onComplete(String worldSlug) {
                if (statusHook != null) {
                    statusHook.markDownloadFinished(worldSlug);
                }
                reload();
            }

            @Override
            public void onFailed(String worldSlug, String reason) {
                if (statusHook != null) {
                    statusHook.markDownloadFinished(worldSlug);
                }
                LazuliMod.LOGGER.warn("Background-only download of cloud-only world \"" + worldSlug + "\" failed: " + reason);
                downloadOnlyStatusMessage = "Download failed: " + reason;
                downloadOnlyStatusSetAtMillis = System.currentTimeMillis();
            }
        });
    }

    /**
     * Requirement 4/Risks: 1.21.11 has no {@code createWorldOpenFlows}-
     * equivalent taking just a save-folder id (confirmed via {@code javap}
     * against this module's own resolved, Yarn-remapped Minecraft jar: no
     * class under {@code net.minecraft.client.gui.screen.world} or {@code
     * MinecraftClient} itself exposes one). Instead of the plan's documented
     * reload-then-find-in-list fallback (which would race {@code
     * WorldListWidget}'s own private async load), this reads a real {@code
     * LevelSummary} synchronously via the same public {@code
     * LevelStorage.Session} API {@link #readLevelDatBatch} already uses, then
     * constructs a real {@code WorldListWidget.WorldEntry} (its 3-arg
     * constructor is public, confirmed via {@code javap}) purely to call its
     * real {@link WorldListWidget.WorldEntry#play()} -- the exact same
     * vanilla play path {@link #playWorld} already uses for a real list row,
     * just without waiting on the list's own async scan.
     */
    private void launchWorld(String worldSlug) {
        try {
            LevelSummary summary;
            try (net.minecraft.world.level.storage.LevelStorage.Session session =
                    MinecraftClient.getInstance().getLevelStorage().createSession(worldSlug)) {
                com.mojang.serialization.Dynamic<?> data = session.readLevelProperties();
                summary = session.getLevelSummary(data);
            }
            WorldListWidget.WorldEntry entry = dataWidget.new WorldEntry(dataWidget, summary);
            playWorld(entry);
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to launch just-restored world \"" + worldSlug + "\": " + e);
        }
    }

    /**
     * FR-V.2/FR-V.7: opens {@code WorldConflictScreen} as a full screen --
     * distinct from {@link #openRestoreFlow}, never opened via that method
     * (FR-V.5). No-op if either the conflict-resolution or restore hook is
     * unavailable (mirrors FR-A.6's own null-hook no-op rule).
     */
    private void openConflictScreen(LevelSummary summary) {
        WorldConflictResolutionHook resolutionHook = WorldConflictHookHolder.getResolutionHookOrNull();
        WorldRestoreHook restoreHook = WorldRestoreHookHolder.getOrNull();
        if (resolutionHook == null || restoreHook == null) {
            return;
        }
        String worldSlug = summary.getName();
        String worldFolderPath = MinecraftClient.getInstance().getLevelStorage().resolve(worldSlug).toAbsolutePath().toString();
        WorldSyncStatusHook statusHook = WorldSyncStatusHookHolder.getOrNull();
        String gameModeDisplayName = summary.getGameMode().getTranslatableName().getString();
        long lastPlayedMillis = summary.getLastPlayed();
        boolean hardcore = summary.isHardcore();
        WorldConflictResolutionHook.LevelDatBatch levelDatBatch = readLevelDatBatch(worldSlug);
        MainMenuScreen.playClickSound();
        MinecraftClient.getInstance().setScreen(new WorldConflictScreen(
                worldSlug, worldFolderPath, summary.getDisplayName(), resolutionHook, restoreHook, statusHook,
                gameModeDisplayName, lastPlayedMillis, hardcore, levelDatBatch, () -> {
                    reload();
                    MinecraftClient.getInstance().setScreen(owner);
                }));
    }

    /**
     * cloud-sync-conflict-ux F10/Decision 5: the batched, one-open
     * {@code level.dat} NBT read -- Yarn/1.21.11's equivalent of
     * {@code LevelStorageSource.LevelStorageAccess.getUnfixedDataTagWithFallback()}
     * is {@code LevelStorage.Session.readLevelProperties()} (confirmed via
     * {@code javap} against this module's own resolved, Yarn-remapped
     * Minecraft jar: {@code net.minecraft.world.level.storage.LevelStorage$Session}
     * has no {@code getUnfixedDataTagWithFallback}/{@code getSummary}-shaped
     * method at all -- a genuine per-mapping-family divergence beyond a
     * simple rename, see {@code minecraft.md}'s Known Cross-Version API
     * Differences table) -- both return the same DataFixerUpper
     * {@code Dynamic<?>} root of {@code level.dat}, read identically here.
     */
    private WorldConflictResolutionHook.LevelDatBatch readLevelDatBatch(String worldSlug) {
        try (net.minecraft.world.level.storage.LevelStorage.Session session =
                MinecraftClient.getInstance().getLevelStorage().createSession(worldSlug)) {
            com.mojang.serialization.Dynamic<?> root = session.readLevelProperties();
            com.mojang.serialization.Dynamic<?> data = root.get("Data").orElseEmptyMap();
            Long seed = data.get("WorldGenSettings").get("seed").asNumber().result().map(Number::longValue).orElse(null);
            int difficultyId = data.get("Difficulty").asNumber().result().map(Number::intValue).orElse(-1);
            String difficulty = switch (difficultyId) {
                case 0 -> "Peaceful";
                case 1 -> "Easy";
                case 2 -> "Normal";
                case 3 -> "Hard";
                default -> null;
            };
            Boolean cheatsEnabled = data.get("allowCommands").asBoolean().result().orElse(null);
            long dayTimeTicks = data.get("DayTime").asNumber().result().map(Number::longValue).orElse(-1L);
            long dayCount = dayTimeTicks >= 0 ? dayTimeTicks / 24000L : -1L;
            String minecraftVersion = data.get("Version").get("Name").asString().result().orElse(null);
            // Requirement 3b: this call site (used by the conflict-detail
            // path) already sources lastPlayedMillis/gameMode/hardcore
            // separately from a real LevelSummary at its own call site
            // (openConflictScreen), so it keeps returning "unavailable" for
            // those 3 fields here -- only SteamCloudSyncClientInitializer's
            // own readLevelDatBatch copy (ordinary background sync) gains
            // real reads for those.
            return new WorldConflictResolutionHook.LevelDatBatch(
                    seed, difficulty, cheatsEnabled, dayCount, minecraftVersion, true, -1L, null, false);
        } catch (Exception e) {
            LazuliMod.LOGGER.warn("Failed to read level.dat batch for world \"" + worldSlug + "\": " + e);
            return WorldConflictResolutionHook.LevelDatBatch.unreadable();
        }
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
