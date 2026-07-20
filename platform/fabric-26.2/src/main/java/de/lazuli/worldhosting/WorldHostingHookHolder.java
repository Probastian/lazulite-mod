package de.lazuli.worldhosting;

import de.lazuli.LazuliMod;
import de.lazuli.features.worldhosting.services.HostingLifecycle;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;

import java.util.function.LongPredicate;

/**
 * Narrow, composition-root-scoped static holder bridging this feature's
 * hosting service ({@link HostingLifecycle}), its friend gate, and the captured
 * Minecraft Netty pipeline into the {@code @Mixin} classes that have no
 * constructor our own code ever calls (same static-holder shape as
 * {@code WorldSyncToggleHookHolder}/{@code SteamworksServiceHandoff}). Published
 * once at startup by {@code SteamWorldHostingClientInitializer}; read by the
 * {@code IntegratedServer}/{@code ServerConnectionListener} mixins.
 *
 * <p>Also owns the host-side {@link SteamSession} lifecycle, since the Netty
 * pipeline args it needs are only available at the same
 * mixin-capture/world-load boundary this holder already straddles.
 */
public final class WorldHostingHookHolder {

    private static volatile HostingLifecycle lifecycle;
    private static volatile LongPredicate canJoin;

    private static volatile ChannelHandler capturedChildHandler;
    private static volatile EventLoopGroup capturedGroup;
    private static volatile SteamSession session;

    private WorldHostingHookHolder() {
    }

    /**
     * Publishes the enabled feature's collaborators. Not called at all when the
     * feature is disabled/Steam unavailable, so every hook below degrades to a
     * no-op (world hosts as vanilla).
     *
     * @param hostingLifecycle the real {@link HostingLifecycle}
     * @param joinGate         {@code gateway::isDirectFriend}-backed FR1.3 gate
     */
    public static void publish(HostingLifecycle hostingLifecycle, LongPredicate joinGate) {
        lifecycle = hostingLifecycle;
        canJoin = joinGate;
    }

    /** @return {@code true} once {@link #publish} has run (feature enabled). */
    public static boolean isEnabled() {
        return lifecycle != null && canJoin != null;
    }

    /**
     * Captures Minecraft's own Netty {@code childHandler}/{@code group} (from
     * {@code ServerConnectionListener.startTcpServerListener}) so a Steam
     * session can reuse the exact same server pipeline. Called by the
     * capture mixin's {@code @ModifyArg}s.
     */
    public static void storeNettyArgs(ChannelHandler childHandler, EventLoopGroup group) {
        capturedChildHandler = childHandler;
        capturedGroup = group;
    }

    /**
     * Starts the Steam P2P listener for a freshly-loaded singleplayer world
     * (FR1.2), reusing the captured pipeline. No-op when the feature is
     * disabled or the pipeline args were not captured.
     */
    public static synchronized void onWorldLoad() {
        if (!isEnabled()) {
            return;
        }
        if (session != null && session.state != SteamSession.State.STOPPED) {
            return;
        }
        ChannelHandler handler = capturedChildHandler;
        EventLoopGroup group = capturedGroup;
        if (handler == null || group == null) {
            LazuliMod.LOGGER.warn("[WorldHosting] Cannot start hosting -- Netty pipeline not captured.");
            return;
        }
        SteamSession newSession = new SteamSession(handler, group, canJoin);
        session = newSession;
        newSession.startAsync();
        lifecycle.start();
    }

    /**
     * Stops the Steam P2P listener when the integrated server stops (world
     * unload/quit, FR1.2/FR2.2). No-op when nothing is hosting.
     */
    public static synchronized void onWorldStop() {
        SteamSession current = session;
        if (current != null) {
            current.stop();
            session = null;
        }
        capturedChildHandler = null;
        capturedGroup = null;
        if (lifecycle != null) {
            lifecycle.stop();
        }
    }

    /** @return {@code true} if a Steam session has at least one connected peer (FR1.4). */
    public static boolean hasConnectedPeers() {
        SteamSession current = session;
        return current != null
                && current.state == SteamSession.State.STARTED
                && current.hasConnectedPeers();
    }
}
