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
 * <p>Docks flush to the screen's right edge (FR4.1/FR4.2) and fills the full
 * available height. Width/height are re-read every frame directly from
 * {@code MinecraftClient.getInstance().getWindow()} rather than cached from
 * a screen-init event -- caching meant a GUI Scale change (which resizes the
 * window without necessarily re-firing screen init) left the sidebar
 * positioned/sized against stale dimensions until the next screen switch.
 *
 * <p>Usage example (from {@code FabricFriendsSidebarInjector}):
 * <pre>{@code
 * FriendSidebarWidget sidebar = new FriendSidebarWidget(facade, avatarTextureCache, this::onRowClicked);
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

    // The hover-to-open handle shown instead of the full avatar strip on
    // non-main-menu/pause screens (see handleOnly).
    private static final int HANDLE_WIDTH = 10;
    private static final int HANDLE_HEIGHT = 28;
    private static final String HANDLE_GLYPH = "F";

    // How long the panel/expanded state survives after the mouse leaves its
    // hover zone before actually collapsing.
    private static final long COYOTE_NANOS = 250_000_000L;

    // Linear animation speeds, in pixels/second real time.
    private static final float WIDTH_ANIM_PX_PER_SECOND = (EXPANDED_WIDTH - COLLAPSED_WIDTH) / 0.12f;

    // Non-status-colored borders (FR4.5/FR4.6) -- opaque grey.
    private static final int SIDEBAR_OUTER_BORDER = 0xFF808080;
    private static final int OWN_PROFILE_SEPARATOR = 0xFF808080;

    private final FriendsSidebarFacade facade;
    private final AvatarTextureCache avatarTextureCache;
    private final RowClickListener rowClickListener;
    private final boolean handleOnly;

    private boolean expanded;
    private boolean panelOpen;
    private int screenWidth = EXPANDED_WIDTH;
    private int screenHeight;
    private int maxRows = DEFAULT_MAX_ROWS;
    private float animatedWidth = COLLAPSED_WIDTH;
    private float scrollPixelOffset;
    private long lastAnimNanos;
    private long lastHoverNanos;

    public interface RowClickListener {
        void onRowClicked(FriendSummary friend, int mouseX, int mouseY, int button, boolean isOwnProfile);
    }

    /**
     * @param handleOnly {@code true} on screens where the sidebar should
     *                   default to a small click-to-open handle instead of
     *                   the always-visible avatar strip (every allow-listed
     *                   screen except the main menu/pause menu) -- FR4.11.
     */
    public FriendSidebarWidget(FriendsSidebarFacade facade, AvatarTextureCache avatarTextureCache,
            RowClickListener rowClickListener, boolean handleOnly) {
        super(0, 0, EXPANDED_WIDTH, listTopOffset() + ROW_HEIGHT * DEFAULT_MAX_ROWS, Text.literal("Friends"));
        this.facade = facade;
        this.avatarTextureCache = avatarTextureCache;
        this.rowClickListener = rowClickListener;
        this.handleOnly = handleOnly;
        this.panelOpen = !handleOnly;
        this.animatedWidth = handleOnly ? HANDLE_WIDTH : COLLAPSED_WIDTH;
    }

    private int handleX() {
        return screenWidth - HANDLE_WIDTH;
    }

    private int handleY() {
        return (screenHeight - HANDLE_HEIGHT) / 2;
    }

    private boolean isOverHandle(double mouseX, double mouseY) {
        int hx = handleX();
        int hy = handleY();
        return mouseX >= hx && mouseX < hx + HANDLE_WIDTH && mouseY >= hy && mouseY < hy + HANDLE_HEIGHT;
    }

    private static int listTopOffset() {
        return ROW_HEIGHT + SEPARATOR_GAP + SEPARATOR_HEIGHT + SEPARATOR_GAP;
    }

    private void refreshScreenSize() {
        var window = MinecraftClient.getInstance().getWindow();
        screenWidth = window.getScaledWidth();
        screenHeight = window.getScaledHeight();
        maxRows = Math.max(1, (screenHeight - listTopOffset()) / ROW_HEIGHT);
    }

    private List<FriendSummary> sortedFriends() {
        return facade.stateMachine().sortForDisplay(facade.friends());
    }

    private int visibleFriendRows(int totalFriends) {
        return Math.min(totalFriends, maxRows);
    }

    private int totalHeight(int totalFriends) {
        return listTopOffset() + Math.max(ROW_HEIGHT, visibleFriendRows(totalFriends) * ROW_HEIGHT);
    }

    private static float moveTowards(float current, float target, float maxDelta) {
        if (Math.abs(target - current) <= maxDelta) {
            return target;
        }
        return current + Math.signum(target - current) * maxDelta;
    }

    /** @return real elapsed seconds since the last call, 0 on the first call */
    private float tickAnimClock() {
        long now = System.nanoTime();
        float dt = lastAnimNanos == 0 ? 0f : (now - lastAnimNanos) / 1_000_000_000f;
        lastAnimNanos = now;
        return dt;
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        // Deliberately empty: this widget is drawn manually, later, via
        // renderNow() from FabricFriendsSidebarInjector's
        // ScreenEvents.afterRender hook, so it always paints on top of the
        // screen's own content (e.g. TitleScreen's logo) instead of
        // whatever order it happens to sit in the normal widget list.
        // Mouse/keyboard input still routes through this widget normally
        // (it stays in Screens.getButtons(screen)), only rendering is moved.
    }

    /**
     * The real render logic, invoked once per frame by
     * {@code FabricFriendsSidebarInjector}'s {@code ScreenEvents.afterRender}
     * hook -- after the screen's own render pass, so this always draws on
     * top.
     */
    public void renderNow(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!facade.isEnabled()) {
            return;
        }
        refreshScreenSize();

        List<FriendSummary> friends = sortedFriends();
        Optional<FriendSummary> own = facade.localProfile();
        int height = totalHeight(friends.size());
        if (friends.size() >= maxRows) {
            height = screenHeight;
        }

        boolean overHandle = handleOnly && isOverHandle(mouseX, mouseY);
        if (overHandle) {
            // Opens on hover, not click -- matches the same hover-driven
            // interaction as the rest of the sidebar.
            panelOpen = true;
        }

        // Hysteresis: while already expanded, test against the wider
        // expanded footprint (so moving left within it doesn't collapse);
        // while collapsed, test against the narrow collapsed footprint.
        int testWidth = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        int testX = screenWidth - testWidth;
        boolean overPanel = panelOpen && facade.stateMachine().isExpanded(mouseX, mouseY, testX, 0, testWidth, height);

        // Coyote time: expanding/opening on hover is instant, but collapsing
        // back only happens after a short grace period with no qualifying
        // hover at all.
        long now = System.nanoTime();
        boolean hovering = overPanel || overHandle;
        if (hovering) {
            lastHoverNanos = now;
        }
        boolean coyoteExpired = lastHoverNanos != 0 && (now - lastHoverNanos) >= COYOTE_NANOS;
        if (coyoteExpired) {
            expanded = false;
            if (handleOnly) {
                panelOpen = false;
            }
        } else if (overPanel) {
            expanded = true;
        }

        // The width animates continuously toward whichever target currently
        // applies -- handle size, collapsed, or expanded -- so opening from
        // (and closing back to) the handle slides just like the
        // collapsed<->expanded transition, instead of snapping.
        float dt = tickAnimClock();
        float targetWidth = (handleOnly && !panelOpen) ? HANDLE_WIDTH : (expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH);
        animatedWidth = moveTowards(animatedWidth, targetWidth, WIDTH_ANIM_PX_PER_SECOND * dt);
        int width = Math.round(animatedWidth);

        // Only once the close animation has actually finished shrinking down
        // to handle size do we switch to drawing the standalone handle --
        // otherwise the panel keeps rendering (shrinking) as normal.
        if (handleOnly && !panelOpen && width <= HANDLE_WIDTH + 1) {
            int hx = handleX();
            int hy = handleY();
            setX(hx);
            setY(hy);
            context.fill(hx, hy, hx + HANDLE_WIDTH, hy + HANDLE_HEIGHT, SIDEBAR_OUTER_BORDER);
            var textRenderer = MinecraftClient.getInstance().textRenderer;
            int glyphX = hx + (HANDLE_WIDTH - textRenderer.getWidth(HANDLE_GLYPH)) / 2;
            int glyphY = hy + (HANDLE_HEIGHT - 8) / 2;
            context.drawTextWithShadow(textRenderer, HANDLE_GLYPH, glyphX, glyphY, 0xFFFFFFFF);
            return;
        }

        boolean showText = expanded && width >= EXPANDED_WIDTH - 2;

        setX(screenWidth - width);
        setY(0);

        context.fill(getX(), getY(), getX() + width, getY() + height, 0x99000000);
        context.fill(getX(), getY(), getX() + BORDER_WIDTH, getY() + height, SIDEBAR_OUTER_BORDER);

        own.ifPresent(profile -> drawRow(context, profile, getX(), getY(), width, showText));

        int separatorY = getY() + ROW_HEIGHT + SEPARATOR_GAP;
        context.fill(getX(), separatorY, getX() + width, separatorY + SEPARATOR_HEIGHT, OWN_PROFILE_SEPARATOR);

        float maxOffsetPx = Math.max(0, friends.size() - maxRows) * (float) ROW_HEIGHT;
        scrollPixelOffset = Math.max(0, Math.min(scrollPixelOffset, maxOffsetPx));

        int rowsTop = getY() + listTopOffset();
        int rowsBottom = getY() + height;
        context.enableScissor(getX(), rowsTop, getX() + width, rowsBottom);
        int startIndex = Math.max(0, (int) (scrollPixelOffset / ROW_HEIGHT));
        float subPixel = scrollPixelOffset - startIndex * (float) ROW_HEIGHT;
        int rowY = rowsTop - Math.round(subPixel);
        for (int i = startIndex; i < friends.size() && rowY < rowsBottom; i++) {
            drawRow(context, friends.get(i), getX(), rowY, width, showText);
            rowY += ROW_HEIGHT;
        }
        context.disableScissor();
    }

    private void drawRow(DrawContext context, FriendSummary friend, int x, int y, int width, boolean showText) {
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

        if (showText) {
            // drawTextWithShadow -> drawText ultimately checks
            // ColorHelper.getAlpha(color) and silently no-ops if it's 0 --
            // same alpha-zero-skip pitfall as the Mojang-mapped side's
            // GuiGraphicsExtractor.text(), just not previously confirmed
            // here. Colors must carry a real (0xFF) alpha byte.
            context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, friend.personaName(),
                    x + ROW_PADDING + DISPLAY_SIZE + 6, y + 2, 0xFFFFFFFF);
            String status = facade.richPresenceStatus(friend.steamId64())
                    .orElseGet(() -> friend.inGame() ? "In Game" : facade.stateMachine().statusLabel(friend.personaState()));
            context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, status,
                    x + ROW_PADDING + DISPLAY_SIZE + 6, y + 11, statusColor);
        }
    }

    private static int personaColor(FriendSummary friend) {
        return friend.personaState() == 0 ? 0xFF808080 : 0xFF33AA33;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (!facade.isEnabled()) {
            return false;
        }
        if (handleOnly && !panelOpen) {
            if (isOverHandle(click.x(), click.y())) {
                panelOpen = true;
                lastHoverNanos = System.nanoTime();
                return true;
            }
            return false;
        }
        if (!isMouseOver(click.x(), click.y())) {
            return false;
        }
        int relativeY = (int) click.y() - getY();
        if (relativeY < ROW_HEIGHT) {
            facade.localProfile().ifPresent(profile ->
                    rowClickListener.onRowClicked(profile, (int) click.x(), (int) click.y(), click.button(), true));
            return true;
        }
        int scrollAreaRelativeY = relativeY - listTopOffset();
        if (scrollAreaRelativeY < 0) {
            return true;
        }
        int index = (int) ((scrollPixelOffset + scrollAreaRelativeY) / ROW_HEIGHT);
        List<FriendSummary> friends = sortedFriends();
        if (index >= 0 && index < friends.size()) {
            rowClickListener.onRowClicked(friends.get(index), (int) click.x(), (int) click.y(), click.button(), false);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!facade.isEnabled() || (handleOnly && !panelOpen)) {
            return false;
        }
        List<FriendSummary> friends = sortedFriends();
        int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        int scrollAreaY = getY() + listTopOffset();
        int scrollAreaHeight = Math.max(ROW_HEIGHT, visibleFriendRows(friends.size()) * ROW_HEIGHT);
        boolean withinScrollArea = mouseX >= getX() && mouseX < getX() + width
                && mouseY >= scrollAreaY && mouseY < scrollAreaY + scrollAreaHeight;
        if (!withinScrollArea) {
            return false;
        }
        // A fraction of a row per notch, rather than a full row, so the
        // wheel supports fine-grained micro-scrolling.
        float step = ROW_HEIGHT / 3f;
        float deltaPx = (float) (-verticalAmount * step);
        scrollPixelOffset = facade.stateMachine().clampScrollPixels(scrollPixelOffset, deltaPx, friends.size(), maxRows, ROW_HEIGHT);
        return true;
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (handleOnly && !panelOpen) {
            return isOverHandle(mouseX, mouseY);
        }
        int height = totalHeight(facade.friends().size());
        int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        return mouseX >= getX() && mouseX < getX() + width && mouseY >= getY() && mouseY < getY() + height;
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(net.minecraft.client.gui.screen.narration.NarrationPart.TITLE, getMessage());
    }
}
