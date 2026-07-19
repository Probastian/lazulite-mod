package de.lazuli.friends;

import de.lazuli.api.friends.FriendSummary;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * The Friends Sidebar's own overlay widget on Minecraft 26.1 (Mojang-mapped,
 * unobfuscated) -- a single custom {@link AbstractWidget} subclass added via
 * {@code Screens.getWidgets(screen)} (implementation plan Decision 1/3, Pattern
 * 1, no mixin). Owns collapsed (avatar-only, FR2.3) vs. expanded
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
 * Screens.getWidgets(screen).add(sidebar);
 * }</pre>
 */
public final class FriendSidebarWidget extends AbstractWidget {

    // The uploaded texture is Steam's full-resolution "large" avatar
    // (AvatarTextureCache.SIZE); DISPLAY_SIZE is the small on-screen icon
    // size we downscale it to when drawing a row.
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
    // consistently regardless of what's drawn behind it (a semi-transparent
    // black looks fine over lighter avatar/text pixels but blends into the
    // near-black background scrim elsewhere, effectively disappearing).
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
        super(0, 0, EXPANDED_WIDTH, listTopOffset() + ROW_HEIGHT * DEFAULT_MAX_ROWS, Component.literal("Friends"));
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
    protected void extractWidgetRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        if (!facade.isEnabled()) {
            return;
        }
        List<FriendSummary> friends = facade.friends();
        Optional<FriendSummary> own = facade.localProfile();
        int height = totalHeight(friends.size());

        // Hover hit-test always against the collapsed-state anchor, not the
        // current (possibly expanded) getX() -- using the live position
        // flip-flops between expanded/collapsed every frame, since expanding
        // shifts the widget's left edge further left and can put the mouse
        // outside that frame's hit box.
        int collapsedX = screenWidth - COLLAPSED_WIDTH;
        expanded = facade.stateMachine().isExpanded(mouseX, mouseY, collapsedX, getY(), COLLAPSED_WIDTH, height);

        int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        setX(screenWidth - width);
        setY(0);

        guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, 0x99000000);
        guiGraphics.fill(getX(), getY(), getX() + BORDER_WIDTH, getY() + height, SIDEBAR_OUTER_BORDER);

        own.ifPresent(profile -> drawRow(guiGraphics, profile, getX(), getY()));

        int separatorY = getY() + ROW_HEIGHT + SEPARATOR_GAP;
        guiGraphics.fill(getX(), separatorY, getX() + width, separatorY + SEPARATOR_HEIGHT, OWN_PROFILE_SEPARATOR);

        int maxOffset = Math.max(0, friends.size() - maxRows);
        scrollOffsetRows = Math.max(0, Math.min(scrollOffsetRows, maxOffset));

        int rowY = getY() + listTopOffset();
        int end = Math.min(friends.size(), scrollOffsetRows + maxRows);
        for (int i = scrollOffsetRows; i < end; i++) {
            drawRow(guiGraphics, friends.get(i), getX(), rowY);
            rowY += ROW_HEIGHT;
        }
    }

    private void drawRow(GuiGraphicsExtractor guiGraphics, FriendSummary friend, int x, int y) {
        int statusColor = facade.stateMachine().statusColorArgb(friend.personaState());
        guiGraphics.fill(x, y, x + BORDER_WIDTH, y + ROW_HEIGHT, statusColor);

        Identifier avatarTexture = avatarTextureCache.getOrUpload(friend.steamId64(),
                facade.avatarRgba(friend.steamId64()).orElse(null));
        if (avatarTexture != null) {
            // Sample the full-resolution source region (regionWidth/Height =
            // the actual texture size) but draw it scaled down to
            // DISPLAY_SIZE -- passing DISPLAY_SIZE as the sample region too
            // would silently crop to that many texels instead of downscaling.
            int size = AvatarTextureCache.SIZE;
            guiGraphics.blit(RenderPipelines.GUI_TEXTURED, avatarTexture,
                    x + ROW_PADDING, y + ROW_PADDING, 0f, 0f, DISPLAY_SIZE, DISPLAY_SIZE, size, size, size, size);
        } else {
            guiGraphics.fill(x + ROW_PADDING, y + ROW_PADDING, x + ROW_PADDING + DISPLAY_SIZE,
                    y + ROW_PADDING + DISPLAY_SIZE, personaColor(friend));
        }

        if (expanded) {
            guiGraphics.text(Minecraft.getInstance().font, friend.personaName(),
                    x + ROW_PADDING + DISPLAY_SIZE + 6, y + 2, 0xFFFFFFFF);
            String status = facade.richPresenceStatus(friend.steamId64())
                    .orElse(facade.stateMachine().statusLabel(friend.personaState()));
            guiGraphics.text(Minecraft.getInstance().font, status,
                    x + ROW_PADDING + DISPLAY_SIZE + 6, y + 11, statusColor);
        }
    }

    private static int personaColor(FriendSummary friend) {
        // Online-ish states render brighter than offline/away -- a plain
        // flat-color placeholder until/unless a real avatar uploads (Decision 5).
        return friend.personaState() == 0 ? 0xFF808080 : 0xFF33AA33;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!facade.isEnabled() || !isMouseOver(event.x(), event.y())) {
            return false;
        }
        int relativeY = (int) event.y() - getY();
        if (relativeY < ROW_HEIGHT) {
            facade.localProfile().ifPresent(profile ->
                    rowClickListener.onRowClicked(profile, (int) event.x(), (int) event.y(), event.button(), true));
            return true;
        }
        int scrollAreaRelativeY = relativeY - listTopOffset();
        if (scrollAreaRelativeY < 0) {
            return true;
        }
        int index = scrollOffsetRows + scrollAreaRelativeY / ROW_HEIGHT;
        List<FriendSummary> friends = facade.friends();
        if (index >= 0 && index < friends.size()) {
            rowClickListener.onRowClicked(friends.get(index), (int) event.x(), (int) event.y(), event.button(), false);
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
        int scrollAreaY = getY() + listTopOffset();
        int scrollAreaHeight = Math.max(ROW_HEIGHT, visibleFriendRows(friends.size()) * ROW_HEIGHT);
        boolean withinScrollArea = mouseX >= getX() && mouseX < getX() + width
                && mouseY >= scrollAreaY && mouseY < scrollAreaY + scrollAreaHeight;
        if (!withinScrollArea) {
            return false;
        }
        int delta = verticalAmount > 0 ? -1 : (verticalAmount < 0 ? 1 : 0);
        scrollOffsetRows = facade.stateMachine().clampScroll(scrollOffsetRows, delta, friends.size(), maxRows);
        return true;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        int height = totalHeight(facade.friends().size());
        int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        return mouseX >= getX() && mouseX < getX() + width && mouseY >= getY() && mouseY < getY() + height;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, getMessage());
    }
}
