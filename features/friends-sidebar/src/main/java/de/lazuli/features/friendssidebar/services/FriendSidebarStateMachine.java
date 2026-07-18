package de.lazuli.features.friendssidebar.services;

import de.lazuli.api.friends.FriendSummary;

/**
 * Plain-JVM-testable hover/expand and context-menu-option-availability logic
 * (NFR1, spec Public API item 3, implementation plan Decision 8). Zero
 * {@code net.minecraft.*}/steamworks4j import.
 *
 * <p>Usage example:
 * <pre>{@code
 * FriendSidebarStateMachine stateMachine = new FriendSidebarStateMachine();
 * boolean expanded = stateMachine.isExpanded(mouseX, mouseY, sidebarX, sidebarY, sidebarWidth, sidebarHeight);
 * boolean inviteEnabled = stateMachine.isInviteEnabled(friend); // always false in v1
 * }</pre>
 */
public final class FriendSidebarStateMachine {

    /**
     * Whole-sidebar hover/expand state (FR2.4): {@code true} whenever the
     * mouse is anywhere within the sidebar's own screen-space bounds -- never
     * per-row.
     *
     * @param mouseX       current mouse X, screen space
     * @param mouseY       current mouse Y, screen space
     * @param sidebarX     the sidebar's left edge, screen space
     * @param sidebarY     the sidebar's top edge, screen space
     * @param sidebarWidth the sidebar's collapsed-state width (the bounds
     *                     used for hover detection are always the collapsed
     *                     footprint, so hovering just inside the edge of an
     *                     already-expanded sidebar doesn't flicker)
     * @param sidebarHeight the sidebar's total height
     * @return {@code true} if the sidebar should render expanded
     */
    public boolean isExpanded(double mouseX, double mouseY, int sidebarX, int sidebarY, int sidebarWidth, int sidebarHeight) {
        return mouseX >= sidebarX && mouseX < sidebarX + sidebarWidth
                && mouseY >= sidebarY && mouseY < sidebarY + sidebarHeight;
    }

    /**
     * @param friend the friend the context menu was opened for (accepted for
     *               forward compatibility -- a future extension may condition
     *               this on {@link FriendSummary#inGame()}/{@link FriendSummary#joinable()})
     * @return {@code true} -- "Open chat" is always enabled in v1 (FR3.1)
     */
    public boolean isOpenChatEnabled(FriendSummary friend) {
        return true;
    }

    /**
     * @param friend the friend the context menu was opened for
     * @return {@code true} -- "Show profile" is always enabled in v1 (FR3.2)
     */
    public boolean isShowProfileEnabled(FriendSummary friend) {
        return true;
    }

    /**
     * @param friend the friend the context menu was opened for
     * @return {@code false} -- "Invite to game" is <strong>always</strong>
     *         disabled in v1 (FR2.6, FR3.3), regardless of
     *         {@link FriendSummary#inGame()}/{@link FriendSummary#joinable()}
     */
    public boolean isInviteEnabled(FriendSummary friend) {
        return false;
    }

    /**
     * @param friend the friend the context menu was opened for
     * @return {@code false} -- "Join game" is <strong>always</strong>
     *         disabled in v1 (FR2.6, FR3.4), regardless of
     *         {@link FriendSummary#inGame()}/{@link FriendSummary#joinable()}
     */
    public boolean isJoinEnabled(FriendSummary friend) {
        return false;
    }
}
