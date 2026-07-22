package de.lazuli.friends;

import de.lazuli.api.friends.FriendSummary;
import de.lazuli.api.richpresence.RichPresenceFacade;
import de.lazuli.features.friendssidebar.api.JoinPolicy;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;
import de.lazuli.ui.DropdownWidget;

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
 * The Friends Sidebar's own overlay widget on Minecraft 26.2 (Mojang-mapped,
 * unobfuscated) -- a single custom {@link AbstractWidget} subclass added via
 * {@code Screens.getWidgets(screen)} (implementation plan Decision 1/3, Pattern
 * 1, no mixin). Owns collapsed (avatar-only, FR2.3) vs. expanded
 * (avatar+name+status, FR2.4/FR4.8) rendering, the pinned own-profile row
 * (FR4.4), the scrollable friends list beneath it (FR4.7), sidebar/separator/
 * per-row status borders (FR4.5/FR4.6/FR4.10), and forwards a row click
 * (either mouse button, FR2.5) to a supplied listener rather than handling
 * the context menu itself (owned by {@code FabricFriendsSidebarInjector}).
 *
 * <p>Docks flush to the screen's right edge (FR4.1/FR4.2) and fills the full
 * available height. Width/height are re-read every frame directly from
 * {@code Minecraft.getInstance().getWindow()} rather than cached from a
 * screen-init event -- caching meant a GUI Scale change (which resizes the
 * window without necessarily re-firing screen init) left the sidebar
 * positioned/sized against stale dimensions until the next screen switch.
 *
 * <p>Usage example (from {@code FabricFriendsSidebarInjector}):
 * <pre>{@code
 * FriendSidebarWidget sidebar = new FriendSidebarWidget(facade, avatarTextureCache, this::onRowClicked);
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

    // The hover-to-open handle shown instead of the full avatar strip on
    // non-main-menu/pause screens (see handleOnly).
    private static final int HANDLE_WIDTH = 10;
    private static final int HANDLE_HEIGHT = 28;
    private static final String HANDLE_GLYPH = "F";

    // Reserves a top strip, on handleOnly screens only, that this overlay
    // never paints into or claims for hit-testing -- other allow-listed
    // screens (JoinMultiplayerScreen today, per server-browser's spec FR
    // "top right of the screen") are free to inject their own top-right
    // button there via Screens.getWidgets without this widget's open/
    // expanded panel (which otherwise starts at y=0 and spans the full
    // right-edge column) visually covering it AND -- the more serious half
    // of the bug this fixes -- without FabricFriendsSidebarInjector's
    // allowMouseClick interceptor swallowing the click before the button
    // ever gets a chance to run (MouseHandlerMixin#invokeMouseClickedEvents
    // skips Screen.mouseClicked entirely, for every widget, whenever any
    // allowMouseClick callback returns false). The main menu/pause menu's
    // always-visible strip (handleOnly == false) keeps the original flush-
    // to-top position since no screen-level button shares that corner there.
    private static final int TOP_INSET = 30;

    // How long the panel/expanded state survives after the mouse leaves its
    // hover zone before actually collapsing -- avoids a collapse from a
    // single-frame mouse blip crossing the boundary.
    private static final long COYOTE_NANOS = 250_000_000L;

    // Linear animation speeds, in pixels/second real time (not tied to tick
    // rate or partial-tick delta, since renderNow() can be called from a
    // screen that isn't ticking).
    private static final float WIDTH_ANIM_PX_PER_SECOND = (EXPANDED_WIDTH - COLLAPSED_WIDTH) / 0.12f;

    // Non-status-colored borders (FR4.5/FR4.6) -- opaque grey.
    private static final int SIDEBAR_OUTER_BORDER = 0xFF808080;
    private static final int OWN_PROFILE_SEPARATOR = 0xFF808080;

    // v1.2 status state (Steam unavailable, FR6.2 outcome 2) -- fills the
    // full screen height like the content state (not a fixed small box), so
    // its footprint reads consistently with the rest of the sidebar; reuses
    // Decision 14's "Busy" red for the indicator since no PersonaState-specific
    // color applies to this feature-level warning.
    private static final int STATUS_INDICATOR_COLOR = 0xFFD54141;
    private static final int STATUS_BORDER_WIDTH = 3;

    // v1.3 amendment: "who can join" dropdown strip's closed/option row
    // height (FR7.3/FR7.4, Decision 1/2) -- owned by this embedding widget,
    // not by DropdownWidget itself (platform/ui/specification.md UI section
    // -- layout constants are the embedder's own choice); background/text
    // colors are now owned entirely by DropdownWidget (v1.4).
    //
    // v2 ("Polish pass") fix: sized for a single line of text (font height +
    // top/bottom ROW_PADDING) rather than reusing the avatar-sized
    // ROW_HEIGHT -- the dropdown never draws an avatar, so reusing
    // ROW_HEIGHT left ~16px of unused vertical space at the bottom of every
    // row. Computed lazily from the live font (rather than a hardcoded
    // magic number) since it's only needed once Minecraft's font is
    // actually available.
    private static int dropdownRowHeight() {
        return Minecraft.getInstance().font.lineHeight + ROW_PADDING * 2;
    }

    // v2 ("Polish pass") fix: horizontal margin between the sidebar's own
    // edges and the dropdown box, so it reads as a discrete floating button
    // rather than being pasted flush onto the sidebar -- reuses ROW_PADDING,
    // the same inset value the rest of this widget's own content (e.g.
    // avatar rows) already uses.
    private static final int DROPDOWN_MARGIN = ROW_PADDING;

    private final FriendsSidebarFacade facade;
    private final AvatarTextureCache avatarTextureCache;
    private final RowClickListener rowClickListener;
    private final boolean handleOnly;
    private final boolean reserveTopInset;
    private final RichPresenceFacade richPresenceFacade;

    private boolean expanded;
    private boolean panelOpen;
    private int screenWidth = EXPANDED_WIDTH;
    private int screenHeight;
    private int maxRows = DEFAULT_MAX_ROWS;
    private float animatedWidth = COLLAPSED_WIDTH;
    private float scrollPixelOffset;
    private long lastAnimNanos;
    private long lastHoverNanos;

    // Keep-expanded-on-open-menu amendment: tracks whether a friend context
    // menu is currently open for this sidebar's screen, so renderNow()'s
    // coyote-time collapse never fires while the menu is open (even though
    // the mouse itself is off the sidebar's own hover bounds).
    private boolean contextMenuOpen;

    // v1.3 amendment: the dropdown strip's own screen-space bounds for this
    // frame (Risk 2) -- null when not rendered (collapsed state), so
    // mouseClicked()'s hit-test and renderNow()'s hover-description use the
    // exact same bounds computed once per frame, never independently.
    private int dropdownX;
    private int dropdownY;
    private int dropdownWidth;
    private boolean dropdownVisible;

    // v1.4 amendment: the join-policy control is now a DropdownWidget
    // (platform/ui/specification.md); lastDropdownHeight caches its
    // reported per-frame total height for listTopOffset() (v1.4-FR7.14) --
    // defaults to the closed-row height before the first render / while not
    // visible, so listTopOffset() never reads an uninitialized 0.
    private final DropdownWidget joinPolicyDropdown;
    private int lastDropdownHeight = dropdownRowHeight();

    public interface RowClickListener {
        void onRowClicked(FriendSummary friend, int mouseX, int mouseY, int button, boolean isOwnProfile);
    }

    /**
     * @param handleOnly {@code true} on screens where the sidebar should
     *                   default to a small click-to-open handle instead of
     *                   the always-visible avatar strip (every allow-listed
     *                   screen except the main menu/pause menu) -- FR4.11.
     * @param reserveTopInset {@code true} only on screens that have their own
     *                   top-right corner button the sidebar/handle must not
     *                   cover (JoinMultiplayerScreen today, per server-
     *                   browser's spec) -- every other handle-only screen
     *                   (e.g. SelectWorldScreen) has no such button and
     *                   should sit flush to the top like the main menu does.
     */
    public FriendSidebarWidget(FriendsSidebarFacade facade, AvatarTextureCache avatarTextureCache,
            RowClickListener rowClickListener, boolean handleOnly, boolean reserveTopInset,
            RichPresenceFacade richPresenceFacade) {
        super(0, 0, EXPANDED_WIDTH, listTopOffset(true, dropdownRowHeight()) + ROW_HEIGHT * DEFAULT_MAX_ROWS, Component.literal("Friends"));
        this.facade = facade;
        this.avatarTextureCache = avatarTextureCache;
        this.rowClickListener = rowClickListener;
        this.handleOnly = handleOnly;
        this.reserveTopInset = reserveTopInset;
        this.richPresenceFacade = richPresenceFacade;
        this.panelOpen = !handleOnly;
        this.animatedWidth = handleOnly ? HANDLE_WIDTH : COLLAPSED_WIDTH;
        // v1.4 amendment: fixed Nobody/Friends/Everyone display order
        // (unchanged from v1.3) mapped explicitly rather than via
        // JoinPolicy.values(), per implementation-plan-v1.4 Risk R1 --
        // JoinPolicy's declared enum order happens to already match this
        // order, but this stays explicit rather than relying on that.
        JoinPolicy[] displayOrder = { JoinPolicy.NOBODY, JoinPolicy.FRIENDS, JoinPolicy.EVERYONE };
        List<DropdownWidget.Option> options = new java.util.ArrayList<>();
        int initialSelectedIndex = 0;
        for (int i = 0; i < displayOrder.length; i++) {
            JoinPolicy policy = displayOrder[i];
            options.add(new DropdownWidget.Option(joinPolicyShortLabel(policy), joinPolicyDescription(policy)));
            if (policy == facade.joinPolicy()) {
                initialSelectedIndex = i;
            }
        }
        this.joinPolicyDropdown = new DropdownWidget("Who can join:", options, initialSelectedIndex,
                index -> facade.selectJoinPolicy(displayOrder[index]));
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

    /**
     * The boundary between the pinned own-profile row and the scrollable
     * friends list. Instance-scoped since v1.3 (Decision 2/Risk 1): includes
     * the join-policy dropdown strip's own height only while {@code expanded}
     * is currently {@code true} for this frame (the dropdown is not rendered
     * at all while collapsed, FR7.3) -- every call site within one
     * {@code renderNow()} invocation observes a consistent value because
     * {@code this.expanded} is only ever mutated earlier in the same method,
     * before any of these call sites run.
     */
    private int listTopOffset() {
        return listTopOffset(expanded, lastDropdownHeight);
    }

    private static int listTopOffset(boolean expanded, int dropdownHeight) {
        // v2 ("Polish pass") reorder: [profile row, gap, (dropdown, gap)
        // while expanded, separator, gap] -- the dropdown now sits ABOVE the
        // separator (between the own-profile row and the separator) instead
        // of below it, so its height/gap are added BEFORE the separator/gap
        // terms rather than after. Must stay in lockstep with renderNow()'s
        // own dropdownY/separatorY arithmetic below.
        int base = ROW_HEIGHT + SEPARATOR_GAP + SEPARATOR_HEIGHT + SEPARATOR_GAP;
        return expanded ? base + dropdownHeight + SEPARATOR_GAP : base;
    }

    /** @see #TOP_INSET */
    private int topInset() {
        return reserveTopInset ? TOP_INSET : 0;
    }

    private void refreshScreenSize() {
        var window = Minecraft.getInstance().getWindow();
        screenWidth = window.getGuiScaledWidth();
        screenHeight = window.getGuiScaledHeight();
        maxRows = Math.max(1, (screenHeight - topInset() - listTopOffset()) / ROW_HEIGHT);
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
    protected void extractWidgetRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        // Deliberately empty: this widget is drawn manually, later, via
        // renderNow() from FabricFriendsSidebarInjector's
        // ScreenEvents.afterExtract hook, so it always paints on top of the
        // screen's own content (e.g. TitleScreen's logo) instead of
        // whatever order it happens to sit in the normal widget list.
        // Mouse/keyboard input still routes through this widget normally
        // (it stays in Screens.getWidgets(screen)), only rendering is moved.
    }

    /**
     * The real render logic, invoked once per frame by
     * {@code FabricFriendsSidebarInjector}'s {@code ScreenEvents.afterExtract}
     * hook -- after the screen's own extract pass, so this always draws on
     * top (FR2.1-adjacent: the sidebar must never render behind other
     * screen content).
     */
    public void notifyContextMenuOpenChanged(boolean open) {
        this.contextMenuOpen = open;
    }

    public void renderNow(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        if (!facade.isEnabled()) {
            dropdownVisible = false;
            return;
        }
        refreshScreenSize();

        boolean steamAvailable = facade.isSteamAvailable();
        List<FriendSummary> friends = steamAvailable ? sortedFriends() : List.of();
        Optional<FriendSummary> own = steamAvailable ? facade.localProfile() : Optional.empty();
        int topInset = topInset();
        int height = steamAvailable ? totalHeight(friends.size()) : screenHeight - topInset;
        if (steamAvailable && friends.size() >= maxRows) {
            // The list fills (or overflows) the available height -- extend
            // exactly to the window edge rather than leaving a rounding
            // remainder below the last row.
            height = screenHeight - topInset;
        }

        boolean overHandle = handleOnly && isOverHandle(mouseX, mouseY);
        if (overHandle) {
            // Opens on hover, not click -- matches the same hover-driven
            // interaction as the rest of the sidebar.
            panelOpen = true;
        }

        // Hysteresis: while already expanded, keep testing against the wider
        // expanded footprint (so moving left within it doesn't collapse);
        // while collapsed, test against the narrow collapsed footprint (so
        // hovering doesn't need to reach all the way to the true edge).
        // Testing against a fixed anchor derived from the *current* expanded
        // state (rather than the live, possibly-just-changed getX()) avoids
        // the position flip-flopping every frame.
        int testWidth = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        int testX = screenWidth - testWidth;
        boolean overPanel = panelOpen && facade.stateMachine().isExpanded(mouseX, mouseY, testX, topInset, testWidth, height);

        // Coyote time: expanding/opening on hover is instant, but collapsing
        // back only happens after a short grace period with no qualifying
        // hover at all, so a brief mouse blip off the edge doesn't instantly
        // close it.
        long now = System.nanoTime();
        boolean hovering = overPanel || overHandle || contextMenuOpen || joinPolicyDropdown.isOpen();
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
            dropdownVisible = false;
            int hx = handleX();
            int hy = handleY();
            setX(hx);
            setY(hy);
            guiGraphics.fill(hx, hy, hx + HANDLE_WIDTH, hy + HANDLE_HEIGHT, SIDEBAR_OUTER_BORDER);
            var font = Minecraft.getInstance().font;
            int glyphX = hx + (HANDLE_WIDTH - font.width(HANDLE_GLYPH)) / 2;
            int glyphY = hy + (HANDLE_HEIGHT - 8) / 2;
            guiGraphics.text(font, HANDLE_GLYPH, glyphX, glyphY, 0xFFFFFFFF);
            return;
        }

        // Only draw names/status once the expand animation has essentially
        // finished -- during the slide there isn't room for them yet.
        boolean showText = expanded && width >= EXPANDED_WIDTH - 2;

        setX(screenWidth - width);
        setY(topInset);

        guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, 0x99000000);
        guiGraphics.fill(getX(), getY(), getX() + BORDER_WIDTH, getY() + height, SIDEBAR_OUTER_BORDER);

        if (!steamAvailable) {
            // FR7.6: no persisted config value is edited from this state --
            // the dropdown is simply not reachable (mouseClicked's own
            // top-of-method isSteamAvailable() gate already covers this).
            // v1.4-FR7.6a: also close the DropdownWidget itself, not merely
            // stop rendering it, so it doesn't reopen stale on the next
            // steamAvailable transition.
            dropdownVisible = false;
            joinPolicyDropdown.close();
            drawStatus(guiGraphics, getX(), getY(), width, height, showText);
            return;
        }

        own.ifPresent(profile -> drawRow(guiGraphics, profile, getX(), getY(), width, showText, Minecraft.getInstance().level != null, richPresenceFacade.localPresenceStatus()));

        // v2 ("Polish pass") reorder: the "who can join" dropdown strip
        // (FR7.3) now renders directly below the own-profile row and ABOVE
        // the separator, instead of below the separator -- only rendered/
        // hit-testable once expanded, reusing the exact same showText
        // condition the avatar-name text already uses (Risk 2), so the
        // render call and the stored click-hit bounds never drift apart
        // across an animation frame. The separator's Y is therefore now
        // conditional on the dropdown's own height, rather than the other
        // way around (must stay in lockstep with listTopOffset() above).
        dropdownVisible = expanded && showText;
        if (dropdownVisible) {
            // v2 ("Polish pass") fix: inset horizontally from the sidebar's
            // own left/right edges so the dropdown reads as a discrete
            // floating button rather than being pasted flush onto the
            // sidebar (consistent with this widget's own ROW_PADDING inset
            // elsewhere).
            dropdownX = getX() + DROPDOWN_MARGIN;
            dropdownY = getY() + ROW_HEIGHT + SEPARATOR_GAP;
            dropdownWidth = width - DROPDOWN_MARGIN * 2;
            lastDropdownHeight = joinPolicyDropdown.render(guiGraphics, dropdownX, dropdownY, dropdownWidth,
                    dropdownRowHeight(), mouseX, mouseY);
        } else {
            dropdownX = 0;
            dropdownY = 0;
            dropdownWidth = 0;
        }

        int separatorY = dropdownVisible
                ? dropdownY + lastDropdownHeight + SEPARATOR_GAP
                : getY() + ROW_HEIGHT + SEPARATOR_GAP;
        guiGraphics.fill(getX(), separatorY, getX() + width, separatorY + SEPARATOR_HEIGHT, OWN_PROFILE_SEPARATOR);

        float maxOffsetPx = Math.max(0, friends.size() - maxRows) * (float) ROW_HEIGHT;
        scrollPixelOffset = Math.max(0, Math.min(scrollPixelOffset, maxOffsetPx));

        int rowsTop = getY() + listTopOffset();
        int rowsBottom = getY() + height;
        guiGraphics.enableScissor(getX(), rowsTop, getX() + width, rowsBottom);
        int startIndex = Math.max(0, (int) (scrollPixelOffset / ROW_HEIGHT));
        float subPixel = scrollPixelOffset - startIndex * (float) ROW_HEIGHT;
        int rowY = rowsTop - Math.round(subPixel);
        for (int i = startIndex; i < friends.size() && rowY < rowsBottom; i++) {
            drawRow(guiGraphics, friends.get(i), getX(), rowY, width, showText, friends.get(i).inGame(), Optional.empty());
            rowY += ROW_HEIGHT;
        }
        guiGraphics.disableScissor();
    }

    /**
     * v1.5 amendment: forwards to {@link DropdownWidget#renderOpenOverlay}
     * when the join-policy dropdown is open, no-op otherwise -- invoked by
     * {@code FabricFriendsSidebarInjector} at the
     * {@link de.lazuli.api.friends.FriendsSidebarZOrder#DROPDOWN_OVERLAY}
     * pass, separately from (and after) this widget's own
     * {@link #renderNow} pass, so the open option list draws on top of the
     * friend-row list rather than being clipped/covered by it (v1.5-FR7.19).
     */
    public void renderDropdownOverlay(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        if (!joinPolicyDropdown.isOpen()) {
            return;
        }
        joinPolicyDropdown.renderOpenOverlay(guiGraphics, mouseX, mouseY, delta);
    }

    /**
     * Renders the v1.2 Steam-unavailable status state (FR6.2 outcome 2, FR6.4-FR6.7)
     * in place of the pinned own-profile row and scrollable friends list.
     * Collapsed: a warning-colored indicator square, same as before. Expanded
     * (Decision 17): a full-height warning-colored left border (mirroring a
     * normal friend row's own status-colored border, {@link #drawRow}) plus
     * {@code facade.steamUnavailableMessage()} -- the indicator must persist
     * in both states, not only while collapsed. Never reads
     * {@code facade.friends()}/{@code facade.localProfile()} (FR6.3(a)).
     */
    private void drawStatus(GuiGraphicsExtractor guiGraphics, int x, int y, int width, int height, boolean showText) {
        if (!showText) {
            guiGraphics.fill(x + ROW_PADDING, y + ROW_PADDING, x + ROW_PADDING + DISPLAY_SIZE,
                    y + ROW_PADDING + DISPLAY_SIZE, STATUS_INDICATOR_COLOR);
            return;
        }
        guiGraphics.fill(x, y, x + STATUS_BORDER_WIDTH, y + height, STATUS_INDICATOR_COLOR);
        var font = Minecraft.getInstance().font;
        int textX = x + STATUS_BORDER_WIDTH + ROW_PADDING;
        int textY = y + ROW_PADDING;
        int maxTextWidth = width - STATUS_BORDER_WIDTH - ROW_PADDING * 2;
        for (String line : wrapMessage(font::width, facade.steamUnavailableMessage(), maxTextWidth)) {
            guiGraphics.text(font, line, textX, textY, 0xFFFFFFFF);
            textY += 10;
        }
    }

    /**
     * Plain greedy word-wrap (FR6.5's "single-line or short, wrapped 2-3
     * lines" allowance) -- avoids depending on any un-`javap`-confirmed
     * {@code Font}-side multi-line layout helper (Risk 16's own tuning-only
     * framing).
     */
    private static List<String> wrapMessage(java.util.function.ToIntFunction<String> widthOf, String message, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : message.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && widthOf.applyAsInt(candidate) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    /**
     * v1.4 amendment: label/description source for {@link DropdownWidget.Option}
     * entries built at construction time (retained verbatim from v1.3 per
     * implementation-plan-v1.4's Architecture item 2) -- the actual
     * rendering of the "who can join" control is now owned entirely by
     * {@link #joinPolicyDropdown}.
     */
    private static String joinPolicyShortLabel(JoinPolicy policy) {
        return switch (policy) {
            case NOBODY -> "Nobody";
            case FRIENDS -> "Friends";
            case EVERYONE -> "Everyone";
        };
    }

    private static String joinPolicyDescription(JoinPolicy policy) {
        return switch (policy) {
            case NOBODY -> "No one can join your hosted world.";
            case FRIENDS -> "Your Steam friends can join your hosted world (default).";
            case EVERYONE -> "Any Steam user can join your hosted world. A real Mojang account is still required to connect.";
        };
    }

    private void drawRow(GuiGraphicsExtractor guiGraphics, FriendSummary friend, int x, int y, int width, boolean showText, boolean inGame, Optional<String> ownRichPresenceOverride) {
        int statusColor = facade.stateMachine().statusColorArgb(friend.personaState(), inGame);
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
                    y + ROW_PADDING + DISPLAY_SIZE, facade.stateMachine().statusColorArgb(friend.personaState(), inGame));
        }

        if (showText) {
            guiGraphics.text(Minecraft.getInstance().font, friend.personaName(),
                    x + ROW_PADDING + DISPLAY_SIZE + 6, y + 2, 0xFFFFFFFF);
            String status = ownRichPresenceOverride
                    .or(() -> facade.richPresenceStatus(friend.steamId64()))
                    .orElseGet(() -> facade.stateMachine().statusLabel(friend.personaState(), inGame));
            guiGraphics.text(Minecraft.getInstance().font, status,
                    x + ROW_PADDING + DISPLAY_SIZE + 6, y + 11, statusColor);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!facade.isEnabled() || !facade.isSteamAvailable()) {
            return false;
        }
        if (handleOnly && !panelOpen) {
            if (isOverHandle(event.x(), event.y())) {
                panelOpen = true;
                lastHoverNanos = System.nanoTime();
                return true;
            }
            return false;
        }
        if (!isMouseOver(event.x(), event.y())) {
            return false;
        }
        if (dropdownVisible && joinPolicyDropdown.mouseClicked(event.x(), event.y(),
                dropdownX, dropdownY, dropdownWidth, dropdownRowHeight())) {
            return true;
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
        int index = (int) ((scrollPixelOffset + scrollAreaRelativeY) / ROW_HEIGHT);
        List<FriendSummary> friends = sortedFriends();
        if (index >= 0 && index < friends.size()) {
            rowClickListener.onRowClicked(friends.get(index), (int) event.x(), (int) event.y(), event.button(), false);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!facade.isEnabled() || !facade.isSteamAvailable() || (handleOnly && !panelOpen)) {
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
        int height = facade.isSteamAvailable() ? totalHeight(facade.friends().size()) : screenHeight - topInset();
        int width = expanded ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        return mouseX >= getX() && mouseX < getX() + width && mouseY >= getY() && mouseY < getY() + height;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, getMessage());
    }
}
