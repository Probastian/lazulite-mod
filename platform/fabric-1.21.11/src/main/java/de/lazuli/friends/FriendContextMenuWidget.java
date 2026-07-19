package de.lazuli.friends;

import de.lazuli.api.friends.FriendSummary;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/**
 * The four-option context menu (Open chat / Show profile / Invite to game /
 * Join game, FR2.6) opened by a friend-row click, drawn above the sidebar
 * (implementation plan Decision 4). Invite/Join always render as disabled
 * placeholders (FR3.3/FR3.4) and never respond to a click, regardless of
 * {@link FriendSummary#inGame()}/{@link FriendSummary#joinable()}.
 *
 * <p>Usage example (from {@code FabricFriendsSidebarInjector}, on a friend row
 * click):
 * <pre>{@code
 * FriendContextMenuWidget menu = new FriendContextMenuWidget(mouseX, mouseY, friend, facade, this::onMenuClosed);
 * Screens.getButtons(screen).add(menu);
 * }</pre>
 */
public final class FriendContextMenuWidget extends ClickableWidget {

    private static final int OPTION_HEIGHT = 16;
    private static final int WIDTH = 110;
    private static final String[] LABELS = {"Open chat", "Show profile", "Invite to game", "Join game"};

    private final FriendSummary friend;
    private final FriendsSidebarFacade facade;
    private final Runnable onClosed;
    private final boolean isOwnProfile;

    public FriendContextMenuWidget(int x, int y, FriendSummary friend, FriendsSidebarFacade facade, Runnable onClosed) {
        this(x, y, friend, facade, onClosed, false);
    }

    /**
     * @param isOwnProfile {@code true} when this menu was opened for the
     *                     pinned own-profile row (FR2.8) -- Open chat/Invite/
     *                     Join are forced disabled and only Show profile is
     *                     enabled, regardless of the state machine's own
     *                     friend-row availability logic
     */
    public FriendContextMenuWidget(int x, int y, FriendSummary friend, FriendsSidebarFacade facade, Runnable onClosed,
            boolean isOwnProfile) {
        super(x, y, WIDTH, OPTION_HEIGHT * LABELS.length, Text.literal("Friend menu"));
        this.friend = friend;
        this.facade = facade;
        this.onClosed = onClosed;
        this.isOwnProfile = isOwnProfile;
    }

    private boolean isEnabled(int index) {
        if (isOwnProfile) {
            // FR2.8: only "Show profile" is ever actionable for one's own row.
            return index == 1;
        }
        return switch (index) {
            case 0 -> facade.stateMachine().isOpenChatEnabled(friend);
            case 1 -> facade.stateMachine().isShowProfileEnabled(friend);
            case 2 -> facade.stateMachine().isInviteEnabled(friend);
            case 3 -> facade.stateMachine().isJoinEnabled(friend);
            default -> false;
        };
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(getX(), getY(), getX() + WIDTH, getY() + getHeight(), 0xEE202020);
        for (int i = 0; i < LABELS.length; i++) {
            int rowY = getY() + i * OPTION_HEIGHT;
            boolean enabled = isEnabled(i);
            boolean hovered = mouseX >= getX() && mouseX < getX() + WIDTH && mouseY >= rowY && mouseY < rowY + OPTION_HEIGHT;
            if (enabled && hovered) {
                context.fill(getX(), rowY, getX() + WIDTH, rowY + OPTION_HEIGHT, 0x55FFFFFF);
            }
            int textColor = enabled ? 0xFFFFFF : 0x808080;
            context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, LABELS[i], getX() + 4, rowY + 4, textColor);
        }
    }

    /**
     * @return {@code true} if {@code (x, y)} is within this menu's own bounds
     */
    public boolean containsPoint(double x, double y) {
        return x >= getX() && x < getX() + WIDTH && y >= getY() && y < getY() + getHeight();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (!containsPoint(click.x(), click.y())) {
            return false;
        }
        int index = ((int) click.y() - getY()) / OPTION_HEIGHT;
        if (index >= 0 && index < LABELS.length && isEnabled(index)) {
            switch (index) {
                case 0 -> facade.actions().onOpenChat(friend.steamId64());
                case 1 -> facade.actions().onShowProfile(friend.steamId64());
                case 2 -> facade.actions().onInvite(friend.steamId64());
                case 3 -> facade.actions().onJoin(friend.steamId64());
                default -> { }
            }
        }
        onClosed.run();
        return true;
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(NarrationPart.TITLE, getMessage());
    }
}
