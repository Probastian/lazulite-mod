package de.lazuli.services.ui;

/**
 * A small, generic, reusable seam for posting a short, non-blocking,
 * auto-dismissing notification -- e.g. vanilla's own toast queue
 * ({@code SystemToast}/{@code ToastManager}) on the platform side.
 *
 * <p>Lives in {@code services/} (not {@code api/}, since a real
 * implementation is inherently Minecraft-rendering-coupled, and not
 * {@code features/friends-sidebar}, since it is not owned by any one
 * Feature's business logic) -- the exact same shape as
 * {@code SteamFriendsGateway}: a plain-typed contract here, backed by one
 * real per-platform-module implementation
 * ({@code platform/fabric-<version>/.../ui/FabricToastService.java}).
 *
 * <p>Usage example:
 * <pre>{@code
 * ToastService toastService = ToastServiceHandoff.require();
 * toastService.post("Invite failed", "Could not send the Steam invite.");
 * }</pre>
 */
public interface ToastService {

    /**
     * Posts a short, non-blocking, auto-dismissing notification.
     *
     * @param title   the toast's title line
     * @param message the toast's message line
     */
    void post(String title, String message);
}
