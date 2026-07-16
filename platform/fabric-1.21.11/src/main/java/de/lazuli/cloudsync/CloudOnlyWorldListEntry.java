package de.lazuli.cloudsync;

import de.lazuli.api.cloudsync.CloudOnlyWorldSummary;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.text.Text;
import net.minecraft.world.level.storage.LevelSummary;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * A plain (non-mixin) subclass of {@link WorldListWidget.Entry} -- the
 * abstract {@code Entry} base type {@link WorldListWidget} already uses --
 * rendering a single cloud-only world (FR6.8/FR6.9) as a distinct synthetic
 * row on the vanilla Singleplayer world-select screen, visually marked with
 * a cloud icon clearly different from Group 6's own on/off sync-toggle icon.
 *
 * <p>{@link WorldListWidget.Entry}'s own no-arg constructor is {@code public}
 * (confirmed via {@code javap} against this repo's actual resolved 1.21.11
 * jar), so subclassing it directly is a completely ordinary, mixin-free Java
 * pattern -- only <em>inserting</em> an instance of this class into the
 * list's private backing collection needs the {@code @Invoker} mixin
 * ({@code WorldListWidgetInvokerMixin}), which
 * {@code FabricCloudOnlyWorldListInjector} uses.
 *
 * <p>{@link #getLevel()} returns {@code null} -- deliberately: this row has
 * no backing on-disk save, so no real {@link LevelSummary} exists for it
 * (FR6.9's own framing).
 *
 * <p>A double-click opens the restore flow (FR6.10) via {@code onPlay},
 * supplied by {@code FabricCloudOnlyWorldListInjector}.
 */
public final class CloudOnlyWorldListEntry extends WorldListWidget.Entry {

    private static final long DOUBLE_CLICK_THRESHOLD_MILLIS = 250L;
    private static final DateTimeFormatter SYNCED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final CloudOnlyWorldSummary summary;
    private final Consumer<CloudOnlyWorldSummary> onPlay;
    private long lastClickTimeMillis = -1L;

    public CloudOnlyWorldListEntry(CloudOnlyWorldSummary summary, Consumer<CloudOnlyWorldSummary> onPlay) {
        this.summary = summary;
        this.onPlay = onPlay;
    }

    @Override
    public LevelSummary getLevel() {
        return null;
    }

    @Override
    public Text getNarration() {
        return Text.literal("Cloud world: " + summary.displayName());
    }

    @Override
    public void render(DrawContext context, int index, int mouseY, boolean hovered, float delta) {
        int x = getContentX();
        int y = getContentY();

        // FR6.9: a cloud icon clearly distinct from the sync-toggle icon -- a simple
        // filled square stand-in until a real texture asset is wired in (Files to
        // Create lists cloud_only.png alongside sync_enabled.png/sync_disabled.png).
        context.fill(x, y + 2, x + 8, y + 10, 0xFF3399FF);

        MinecraftClient client = MinecraftClient.getInstance();
        context.drawText(client.textRenderer, summary.displayName(), x + 14, y + 1, 0xFFFFFF, false);
        String detail = summary.deviceLabel() + " - " + formatSyncedAt(summary.syncedAtTimestamp());
        context.drawText(client.textRenderer, detail, x + 14, y + 12, 0xA0A0A0, false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        long now = System.currentTimeMillis();
        boolean isDoubleClick = doubled || (lastClickTimeMillis >= 0 && now - lastClickTimeMillis <= DOUBLE_CLICK_THRESHOLD_MILLIS);
        lastClickTimeMillis = now;
        if (isDoubleClick) {
            onPlay.accept(summary);
            return true;
        }
        return false;
    }

    public CloudOnlyWorldSummary summary() {
        return summary;
    }

    public static String formatSyncedAt(long syncedAtTimestamp) {
        return SYNCED_AT_FORMAT.format(Instant.ofEpochMilli(syncedAtTimestamp));
    }
}
