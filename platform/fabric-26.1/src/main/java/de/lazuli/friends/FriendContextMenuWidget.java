package de.lazuli.friends;

import de.lazuli.api.friends.FriendSummary;
import de.lazuli.api.worldhosting.FriendHostingStatusReader;
import de.lazuli.api.worldhosting.WorldInviteSender;
import de.lazuli.api.worldhosting.WorldJoinRequester;
import de.lazuli.features.friendssidebar.services.FriendsSidebarFacade;
import de.lazuli.services.ui.ToastService;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

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
 * Screens.getWidgets(screen).add(menu);
 * }</pre>
 */
public final class FriendContextMenuWidget extends AbstractWidget {

    private static final int OPTION_HEIGHT = 16;
    public static final int WIDTH = 110;
    private static final String[] LABELS = {"Open chat", "Show profile", "Invite to game", "Join game"};
    public static final int HEIGHT = OPTION_HEIGHT * LABELS.length;

    private final FriendSummary friend;
    private final FriendsSidebarFacade facade;
    private final Runnable onClosed;
    private final boolean isOwnProfile;
    private final WorldJoinRequester worldJoinRequester;
    private final FriendHostingStatusReader hostingStatusReader;
    private final WorldInviteSender worldInviteSender;
    private final ToastService toastService;

    public FriendContextMenuWidget(int x, int y, FriendSummary friend, FriendsSidebarFacade facade, Runnable onClosed) {
        this(x, y, friend, facade, onClosed, false, null, null, null, null);
    }

    /**
     * @param isOwnProfile        {@code true} when this menu was opened for the
     *                            pinned own-profile row (FR2.8) -- Open chat/
     *                            Invite/Join are forced disabled and only Show
     *                            profile is enabled
     * @param worldJoinRequester  Steam World Hosting's join operation for the
     *                            reused "Join game" slot (Steam World Hosting
     *                            FR4.1/FR4.3), or {@code null} if that feature
     *                            is absent/disabled -- then "Join game" stays a
     *                            disabled placeholder
     * @param hostingStatusReader gate for the "Join game" slot's enablement
     *                            (Steam World Hosting FR4.2), or {@code null}
     * @param worldInviteSender   Steam World Hosting's invite operation for the
     *                            reused "Invite to game" slot
     *                            (specification-invite-to-game.md FR-INV1/
     *                            FR-INV4), or {@code null} if that feature is
     *                            absent/disabled -- then "Invite to game" stays
     *                            a disabled placeholder
     * @param toastService        failure-feedback sink for a failed invite send
     *                            (FR-INV8), or {@code null}
     */
    public FriendContextMenuWidget(int x, int y, FriendSummary friend, FriendsSidebarFacade facade, Runnable onClosed,
            boolean isOwnProfile, WorldJoinRequester worldJoinRequester, FriendHostingStatusReader hostingStatusReader,
            WorldInviteSender worldInviteSender, ToastService toastService) {
        super(x, y, WIDTH, OPTION_HEIGHT * LABELS.length, Component.literal("Friend menu"));
        this.friend = friend;
        this.facade = facade;
        this.onClosed = onClosed;
        this.isOwnProfile = isOwnProfile;
        this.worldJoinRequester = worldJoinRequester;
        this.hostingStatusReader = hostingStatusReader;
        this.worldInviteSender = worldInviteSender;
        this.toastService = toastService;
    }

    private boolean isEnabled(int index) {
        if (isOwnProfile) {
            // FR2.8: only "Show profile" is ever actionable for one's own row.
            return index == 1;
        }
        return switch (index) {
            case 0 -> facade.stateMachine().isOpenChatEnabled(friend);
            case 1 -> facade.stateMachine().isShowProfileEnabled(friend);
            // FR-INV1: the reused "Invite to game" slot is enabled only when
            // the local player currently has an active hosted session. Reads
            // the bridge directly (bypasses the always-false, dead-code
            // FriendSidebarStateMachine.isInviteEnabled), mirroring "Join
            // game"'s own case 3 precedent below.
            case 2 -> worldInviteSender != null && worldInviteSender.isHosting();
            // FR4.1/FR4.2: the reused "Join game" slot is enabled only when
            // Steam World Hosting reports this friend as currently hosting.
            case 3 -> hostingStatusReader != null && hostingStatusReader.isFriendHosting(friend.steamId64());
            default -> false;
        };
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        // Deliberately empty -- drawn manually via renderNow() from
        // FabricFriendsSidebarInjector's after-render hook, after the
        // sidebar itself, so the menu always draws on top of it
        // (FriendsSidebarZOrder.CONTEXT_MENU > SIDEBAR).
    }

    /** The real render logic; see {@link #extractWidgetRenderState}. */
    public void renderNow(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
        guiGraphics.fill(getX(), getY(), getX() + WIDTH, getY() + getHeight(), 0xEE202020);
        for (int i = 0; i < LABELS.length; i++) {
            int rowY = getY() + i * OPTION_HEIGHT;
            boolean enabled = isEnabled(i);
            boolean hovered = mouseX >= getX() && mouseX < getX() + WIDTH && mouseY >= rowY && mouseY < rowY + OPTION_HEIGHT;
            if (enabled && hovered) {
                guiGraphics.fill(getX(), rowY, getX() + WIDTH, rowY + OPTION_HEIGHT, 0x55FFFFFF);
            }
            int textColor = enabled ? 0xFFFFFFFF : 0xFF808080;
            guiGraphics.text(Minecraft.getInstance().font, LABELS[i], getX() + 4, rowY + 4, textColor);
        }
    }

    /**
     * @return {@code true} if {@code (x, y)} is within this menu's own bounds
     */
    public boolean containsPoint(double x, double y) {
        return x >= getX() && x < getX() + WIDTH && y >= getY() && y < getY() + getHeight();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!containsPoint(event.x(), event.y())) {
            return false;
        }
        int index = ((int) event.y() - getY()) / OPTION_HEIGHT;
        if (index >= 0 && index < LABELS.length && isEnabled(index)) {
            switch (index) {
                case 0 -> facade.actions().onOpenChat(friend.steamId64());
                case 1 -> facade.actions().onShowProfile(friend.steamId64());
                // FR-INV4/FR-INV8: route the reused "Invite to game" slot to
                // Steam World Hosting's invite operation directly (bypasses
                // the friends-sidebar action listener, mirroring case 3's own
                // bypass of onJoin), surfacing a toast on a failed send.
                case 2 -> {
                    if (worldInviteSender != null && !worldInviteSender.inviteFriend(friend.steamId64())) {
                        if (toastService != null) {
                            toastService.post("Invite failed",
                                    "Could not send the Steam invite. Check that the Steam overlay is enabled.");
                        }
                    }
                }
                // FR4.3: route the reused "Join game" slot to Steam World
                // Hosting's join operation instead of the friends-sidebar no-op.
                case 3 -> {
                    if (worldJoinRequester != null) {
                        worldJoinRequester.joinHostedWorld(friend.steamId64());
                    }
                }
                default -> { }
            }
        }
        onClosed.run();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        narrationElementOutput.add(NarratedElementType.TITLE, getMessage());
    }
}
