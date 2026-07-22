package de.lazuli.worldhosting;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamNetworking;

import de.lazuli.LazuliMod;

import io.netty.channel.AbstractServerChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;

/**
 * Host-side Netty {@code ServerChannel} backed by Steam P2P. Opens no real OS
 * socket; when a remote peer is accepted (via
 * {@link SteamSession}'s {@code onP2PSessionRequest} callback) it applies the
 * friend-relationship gate (FR1.3) and, on acceptance, fires a new
 * {@link SteamNettyChannel} child into the same pipeline
 * {@code ServerConnectionListener} already wired for real TCP. Ported from the
 * prototype's own {@code SteamServerChannel} ({@code JoinPolicy}/FoF removed,
 * replaced by a plain injected {@link LongPredicate}).
 */
public final class SteamServerChannel extends AbstractServerChannel {

    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final SteamNetworking networking;

    /** The owning session -- used to route inbound packets and share the lock. */
    final SteamSession session;

    /** FR1.3 gate: {@code true} iff the given {@code SteamID64} may join. */
    private final AtomicReference<LongPredicate> canJoin;

    private volatile boolean closed = false;

    public SteamServerChannel(SteamSession session, SteamNetworking networking, AtomicReference<LongPredicate> canJoin) {
        this.session = session;
        this.networking = networking;
        this.canJoin = canJoin;
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return new SteamAddress(0L);
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        // No-op: Steam P2P needs no real bind.
    }

    @Override
    protected void doClose() {
        closed = true;
    }

    @Override
    protected void doBeginRead() {
        // Incoming peer connections are pushed by acceptPeer().
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public boolean isActive() {
        return !closed;
    }

    /**
     * Applies the FR1.3 friend gate to an inbound P2P session request. A
     * non-friend is rejected (a FIN reason is sent and the session closed)
     * without ever reaching the Minecraft handshake; a friend is accepted and
     * wired up as a new child channel.
     *
     * @param remotePeer the requesting peer
     */
    public void acceptPeer(SteamID remotePeer) {
        long peerId = SteamNativeHandle.getNativeHandle(remotePeer);

        if (!canJoin.get().test(peerId)) {
            LazuliMod.LOGGER.info("[WorldHosting] Rejecting peer {} -- not a direct Steam friend", peerId);
            SteamDisconnectProtocol.sendFin(networking, remotePeer, "lazuli.worldhosting.disconnect.not_friend");
            Thread closer = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                synchronized (session.steamLock) {
                    networking.closeP2PSessionWithUser(remotePeer);
                }
            }, "lazuli-worldhosting-reject-close");
            closer.setDaemon(true);
            closer.start();
            return;
        }

        if (!networking.acceptP2PSessionWithUser(remotePeer)) {
            LazuliMod.LOGGER.error("[WorldHosting] acceptP2PSessionWithUser failed for peer {}", peerId);
            return;
        }
        LazuliMod.LOGGER.info("[WorldHosting] Accepted peer {}", peerId);

        SteamNettyChannel child = new SteamNettyChannel(this, remotePeer, networking);
        session.registerChild(child);
        // fireChannelRead(child) triggers Netty's own ServerBootstrapAcceptor
        // (registered internally by ServerBootstrap.childHandler(...)), which
        // asynchronously calls childGroup().register(child, ...). Netty then
        // fires channelActive itself once that registration completes and
        // isActive() is true (already the case here) -- firing it manually,
        // synchronously, right here throws "channel not registered to an
        // event loop" since the async registration hasn't run yet.
        pipeline().fireChannelRead(child);
        pipeline().fireChannelReadComplete();
    }
}
