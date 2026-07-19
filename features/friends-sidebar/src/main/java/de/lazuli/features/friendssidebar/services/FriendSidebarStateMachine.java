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

    /**
     * Pure clamp/accumulate for the scrollable friends list (FR4.7) -- the
     * pinned own-profile row never scrolls, this only governs the friends
     * list below it. Offsets are in whole rows, not pixels, so this stays
     * resolution/{@code ROW_HEIGHT}-agnostic.
     *
     * @param currentScrollOffset current offset, in rows
     * @param deltaRows           signed row delta from one scroll-wheel event
     * @param totalRows           total friend-row count
     * @param visibleRows         how many friend rows currently fit in the
     *                            sidebar's scrollable region
     * @return the new offset, clamped to {@code [0, max(0, totalRows - visibleRows)]}
     */
    public int clampScroll(int currentScrollOffset, int deltaRows, int totalRows, int visibleRows) {
        int maxOffset = Math.max(0, totalRows - visibleRows);
        int next = currentScrollOffset + deltaRows;
        return Math.max(0, Math.min(next, maxOffset));
    }

    /**
     * Maps a {@code SteamFriends.PersonaState} ordinal (0-7, see
     * {@code FriendSummary#personaState()}) to Steam's own status-color
     * convention (FR4.9/FR4.10) -- full-alpha ARGB, {@code 0xFF} alpha byte
     * always set. {@code Online}/{@code LookingToTrade}/{@code LookingToPlay}
     * share one green; {@code Offline}/{@code Invisible} share one grey.
     *
     * @param personaState the friend's/own profile's persona-state ordinal
     * @return a full-alpha {@code 0xFFxxxxxx} ARGB color
     */
    public int statusColorArgb(int personaState) {
        return switch (personaState) {
            case 1, 5, 6 -> 0xFF5BA32F; // Online, LookingToTrade, LookingToPlay
            case 3, 4 -> 0xFFE3A008;    // Away, Snooze
            case 2 -> 0xFFD54141;       // Busy
            default -> 0xFF898989;     // Offline (0) / Invisible (7)
        };
    }

    /**
     * @param personaState the friend's/own profile's persona-state ordinal
     * @return the plain-text status word for that state (FR1.8/FR4.8) --
     *         used whenever no Rich Presence value is available
     */
    public String statusLabel(int personaState) {
        return switch (personaState) {
            case 1 -> "Online";
            case 2 -> "Busy";
            case 3 -> "Away";
            case 4 -> "Snooze";
            case 5 -> "Looking to Trade";
            case 6 -> "Looking to Play";
            case 7 -> "Invisible";
            default -> "Offline";
        };
    }
}
