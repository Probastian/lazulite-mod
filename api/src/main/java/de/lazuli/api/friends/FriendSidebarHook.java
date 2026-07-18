package de.lazuli.api.friends;

import java.util.List;

/**
 * Minecraft-agnostic surface a platform Version Adapter implements to receive
 * this feature's own friend-list snapshots and enabled/disabled state (spec
 * Public API item 1), the same "Platform API" shape already established by
 * {@code de.lazuli.api.mainmenu.MainMenuHook}.
 *
 * <p>Usage example (feature-side code pushing a fresh snapshot after a
 * refresh sweep):
 * <pre>{@code
 * FriendSidebarHook hook = ...; // supplied by the platform composition root
 * hook.updateFriends(friendsService.currentFriends());
 * }</pre>
 */
public interface FriendSidebarHook {

    /**
     * @param friends the full, current friend-list snapshot to render
     */
    void updateFriends(List<FriendSummary> friends);

    /**
     * @param enabled whether the sidebar should currently be shown at all
     *                (FR0.2/FR0.3 -- {@code false} when Steam is unavailable
     *                or the feature's own config disables it)
     */
    void setEnabled(boolean enabled);
}
