package de.lazuli.friends;

import de.lazuli.api.friends.FriendSummary;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * The Friends Sidebar's own overlay widget on Minecraft 1.21.11 (Yarn-mapped,
 * obfuscated) -- a single custom {@link ClickableWidget} subclass added via
 * {@code Screens.getButtons(screen)} (implementation plan Decision 1/3,
 * Pattern 1, no mixin). Owns collapsed (avatar-only, FR2.3) vs. expanded
 * (avatar+name+status, FR2.4/FR4.8) rendering, the pinned own-profile row
 * (FR4.4), the scrollable friends list beneath it (FR4.7), sidebar/separator/
 * per-row status borders (FR4.5/FR4.6/FR4.10), and forwards a row click
 * (either mouse button, FR2.5) to a supplied listener rather than handling
 * the context menu itself (owned by {@code FabricFriendsSidebarInjector}).
 *
 * <p>Docks flush to the screen's right edge (FR4.1/FR4.2) -- {@code x} is
 * recomputed every frame from the last {@link #setScreenWidth(int)} value
 * and the sidebar's current (collapsed/expanded) width, so the right edge
 * never moves; {@code y} is always {@code 0} (flush to the top).
 *
 * <p>Usage example (from {@code FabricFriendsSidebarInjector}):
 * <pre>{@code
 * FriendSidebarWidget sidebar = new FriendSidebarWidget(facade, avatarTextureCache, this::onRowClicked);
 * sidebar.setScreenWidth(scaledWidth);
 * Screens.getButtons(screen).add(sidebar);
 * }</pre>
 */
public final class FriendSidebarWidget extends ClickableWidget {

    // The uploaded texture is Steam's full-resolution "large" avatar
    // (AvatarTextureCache.SIZE); DISPLAY_SIZE is the small on-screen icon
    // size we downscale it to when drawing a row. drawTexturedQuad's u/v
    // range is always the whole 0..1 texture span, so no separate sample
    // region parameter is needed here to get the downscale.
    private static final int DISPLAY_SIZE = 22;
    private static final int ROW_PADDING = 3;
    private static final int ROW_HEIGHT = DISPLAY_SIZE + ROW_PADDING * 2;
    private static final int COLLAPSED_WIDTH = DISPLAY_SIZE + ROW_PADDING * 2;
    private static final int EXPANDED_WIDTH = 180;
    private static final int SEPARATOR_HEIGHT = 1;
    private static final int SEPARATOR_GAP = 2;
    private static final int BORDER_WIDTH = 1;
    private static final int DEFAULT_MAX_ROWS = 12;

    // Non-status-colored borders (FR4.5/FR4.6) -- opaque grey so it reads
    // consistently regardless of what's drawn behind it.
    private static final int SIDEBAR_OUTER_BORDER = 0xFFAAAAAA;
    private static final int OWN_PROFILE_SEPARATOR = 0xFFAAAAAA;

    private final FriendsSidebarFacade facade;
    private final AvatarTextureCache avatarTextureCache;
    private final RowClickListener rowClickListener;

    private boolean expanded;
    private int screenWidth = EXPANDED_WIDTH;
    private int maxRows = DEFAULT_MAX_ROWS;
    private int scrollOffsetRows;

    public interface RowClickListener {
        void onRowClicked(FriendSummary friend, int mouseX, int mouseY, int button, boolean isOwnProfile);
    }

    public FriendSidebarWidget(FriendsSidebarFacade facade, AvatarTextureCache avatarTextureCache,
            RowClickListener rowClickListener) {
        super(0, 0, EXPANDED_WIDTH, listTopOffset() + ROW_HEIGHT * DEFAULT_MAX_ROWS, Text.literal("Friends"));
        this.facade = facade;
        this.avatarTextureCache = avatarTextureCache;
        this.rowClickListener = rowClickListener;
    }

    private static int listTopOffset() {
        return ROW_HEIGHT + SEPARATOR_GAP + SEPARATOR_HEIGHT + SEPARATOR_GAP;
    }

    /**
     * Called by {@code FabricFriendsSidebarInjector.onScreenInit(...)} once
     * per (re-)init -- the current screen's size, used to keep the sidebar's
     * right edge flush (FR4.1/FR4.2) and to fill the full available height
     * with rows regardless of GUI Scale, rather than a fixed row count.
     */
    public void setScreenSize(int scaledWidth, int scaledHeight) {
        this.screenWidth = scaledWidth;
        this.maxRows = Math.max(1, (scaledHeight - listTopOffset()) / ROW_HEIGHT);
    }

    private int visibleFriendRows(int totalFriends) {
        return Math.min(totalFriends, maxRows);
    }

    private int totalHeight(int totalFriends) {
        return listTopOffset() + Math.max(ROW_HEIGHT, visibleFriendRows(totalFriends) * ROW_HEIGHT);
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!facade.isEnabled()) {
            return;
        }
        List<FriendSummary> friends = facade.friends();
        Optional<FriendSummary> own = facade.localProfile();
        int height = totalHeight(friends.size());

        // Hover hit-test always against the collapsed-state anchor -- using
        // the live (possibly expanded) getX() flip-flops every frame.
        int collapsedX = screenWidth - COLLAPSED_WIDTH;
        expanded = facade.stateMachine().isExpanded(mouseX, mouseY, collapsedX, getY(), COLLAPSED_WIDTH, height);

        int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        setX(screenWidth - width);
        setY(0);

        context.fill(getX(), getY(), getX() + width, getY() + height, 0x99000000);
        context.fill(getX(), getY(), getX() + BORDER_WIDTH, getY() + height, SIDEBAR_OUTER_BORDER);

        own.ifPresent(profile -> drawRow(context, profile, getX(), getY()));

        int separatorY = getY() + ROW_HEIGHT + SEPARATOR_GAP;
        context.fill(getX(), separatorY, getX() + width, separatorY + SEPARATOR_HEIGHT, OWN_PROFILE_SEPARATOR);

        int maxOffset = Math.max(0, friends.size() - maxRows);
        scrollOffsetRows = Math.max(0, Math.min(scrollOffsetRows, maxOffset));

        int rowY = getY() + listTopOffset();
        int end = Math.min(friends.size(), scrollOffsetRows + maxRows);
        for (int i = scrollOffsetRows; i < end; i++) {
            drawRow(context, friends.get(i), getX(), rowY);
            rowY += ROW_HEIGHT;
        }
    }

    private void drawRow(DrawContext context, FriendSummary friend, int x, int y) {
        int statusColor = facade.stateMachine().statusColorArgb(friend.personaState());
        context.fill(x, y, x + BORDER_WIDTH, y + ROW_HEIGHT, statusColor);

        Identifier avatarTexture = avatarTextureCache.getOrUpload(friend.steamId64(),
                facade.avatarRgba(friend.steamId64()).orElse(null));
        if (avatarTexture != null) {
            context.drawTexturedQuad(avatarTexture, x + ROW_PADDING, y + ROW_PADDING,
                    x + ROW_PADDING + DISPLAY_SIZE, y + ROW_PADDING + DISPLAY_SIZE, 0f, 1f, 0f, 1f);
        } else {
            context.fill(x + ROW_PADDING, y + ROW_PADDING, x + ROW_PADDING + DISPLAY_SIZE,
                    y + ROW_PADDING + DISPLAY_SIZE, personaColor(friend));
        }

        if (expanded) {
            context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, friend.personaName(),
                    x + ROW_PADDING + DISPLAY_SIZE + 6, y + 2, 0xFFFFFF);
            String status = facade.richPresenceStatus(friend.steamId64())
                    .orElse(facade.stateMachine().statusLabel(friend.personaState()));
            // drawTextWithShadow on this side takes a plain RGB int (alpha
            // implied opaque by the method itself) -- mask off the alpha
            // byte statusColorArgb() always sets, same convention already
            // established by this row's own persona-name draw call above.
            context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, status,
                    x + ROW_PADDING + DISPLAY_SIZE + 6, y + 11, statusColor & 0xFFFFFF);
        }
    }

    private static int personaColor(FriendSummary friend) {
        return friend.personaState() == 0 ? 0xFF808080 : 0xFF33AA33;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (!facade.isEnabled() || !isMouseOver(click.x(), click.y())) {
            return false;
        }
        int relativeY = (int) click.y() - getY();
        if (relativeY < ROW_HEIGHT) {
            facade.localProfile().ifPresent(profile ->
                    rowClickListener.onRowClicked(profile, (int) click.x(), (int) click.y(), click.button(), true));
            return true;
        }
        int scrollAreaRelativeY = relativeY - ROW_HEIGHT - SEPARATOR_HEIGHT;
        if (scrollAreaRelativeY < 0) {
            return true;
        }
        int index = scrollOffsetRows + scrollAreaRelativeY / ROW_HEIGHT;
        List<FriendSummary> friends = facade.friends();
        if (index >= 0 && index < friends.size()) {
            rowClickListener.onRowClicked(friends.get(index), (int) click.x(), (int) click.y(), click.button(), false);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!facade.isEnabled()) {
            return false;
        }
        List<FriendSummary> friends = facade.friends();
        int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        int scrollAreaY = getY() + ROW_HEIGHT + SEPARATOR_HEIGHT;
        int scrollAreaHeight = Math.max(ROW_HEIGHT, visibleFriendRows(friends.size()) * ROW_HEIGHT);
        boolean withinScrollArea = mouseX >= getX() && mouseX < getX() + width
                && mouseY >= scrollAreaY && mouseY < scrollAreaY + scrollAreaHeight;
        if (!withinScrollArea) {
            return false;
        }
        int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
        scrollOffsetRows = facade.stateMachine().clampScroll(scrollOffsetRows, delta, friends.size(), MAX_ROWS);
        return true;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        int height = totalHeight(facade.friends().size());
        int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        return mouseX >= getX() && mouseX < getX() + width && mouseY >= getY() && mouseY < getY() + height;
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(net.minecraft.client.gui.screen.narration.NarrationPart.TITLE, getMessage());
    }
}
