package de.lazuli.features.worldhosting.services;

import de.lazuli.api.worldhosting.WorldInviteSender;

/**
 * A {@link WorldInviteSender} that does nothing -- used whenever Steam is
 * unavailable or this feature's config disables it (FR0.2/FR0.3), so the
 * reused Friends Sidebar "Invite to game" slot stays disabled and
 * {@code WorldHostingBridgeHandoff.requireWorldInviteSender()} never returns
 * {@code null}.
 */
public final class NoopWorldInviteSender implements WorldInviteSender {

    @Override
    public boolean isHosting() {
        return false;
    }

    @Override
    public boolean inviteFriend(long friendSteamId64) {
        return false;
    }
}
