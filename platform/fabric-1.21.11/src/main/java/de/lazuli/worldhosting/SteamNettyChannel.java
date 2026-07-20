package de.lazuli.worldhosting;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamNetworking;

import de.lazuli.LazuliMod;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.util.internal.StringUtil;

import java.net.SocketAddress;
import java.nio.ByteBuffer;

/**
 * A bidirectional Netty channel backed by Steam {@code ISteamNetworking} P2P,
 * used on both sides: host-side as a child of {@link SteamServerChannel} per
 * accepted peer, client-side created by the client-connect Bootstrap hijack
 * mixin. Ported from the prototype's own {@code SteamNettyChannel} (FoF removed).
 *
 * <p>Threading: writes happen on the Netty EventLoop; reads are pushed from a
 * poller thread ({@link SteamSession#pollPackets()} host-side,
 * {@link #pollRead()} client-side). All native calls are guarded by
 * {@link #steamLock}.
 */
public final class SteamNettyChannel extends AbstractChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private final ChannelConfig config = new DefaultChannelConfig(this);

    public static final int STEAM_CHANNEL = 0;
    public static final int MAX_PACKET = 512 * 1024;

    volatile SteamID peer;
    volatile SteamNetworking networking;

    /** Guards all native P2P calls for this channel's networking instance. */
    volatile Object steamLock = new Object();

    private volatile boolean closed = false;
    /** Set by {@link SteamSession#stop()} after it already broadcast the FIN. */
    volatile boolean finAlreadySent = false;

    /** Client-side: parent = null, peer set later in the Unsafe.connect(). */
    public SteamNettyChannel() {
        super(null);
    }

    /** Host-side: parent = the {@link SteamServerChannel}. */
    SteamNettyChannel(SteamServerChannel parent, SteamID peer, SteamNetworking networking) {
        super(parent);
        this.peer = peer;
        this.networking = networking;
        this.steamLock = parent.session.steamLock;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new SteamUnsafe();
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
    protected SocketAddress remoteAddress0() {
        SteamID p = peer;
        return new SteamAddress(p != null ? SteamNativeHandle.getNativeHandle(p) : 0L);
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
        throw new UnsupportedOperationException("SteamNettyChannel does not support bind()");
    }

    @Override
    protected void doDisconnect() {
        doClose();
    }

    @Override
    protected void doClose() {
        if (closed) {
            return;
        }
        closed = true;
        SteamNetworking net = networking;
        SteamID p = peer;
        networking = null;
        peer = null;

        if (net != null && p != null) {
            if (finAlreadySent) {
                synchronized (steamLock) {
                    net.closeP2PSessionWithUser(p);
                }
            } else {
                synchronized (steamLock) {
                    SteamDisconnectProtocol.sendFin(net, p);
                }
                final SteamNetworking netFinal = net;
                final SteamID pFinal = p;
                Thread closer = new Thread(() -> {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    synchronized (steamLock) {
                        netFinal.closeP2PSessionWithUser(pFinal);
                    }
                }, "lazuli-worldhosting-session-close");
                closer.setDaemon(true);
                closer.start();
            }
        }

        pipeline().fireChannelInactive();
    }

    @Override
    protected void doBeginRead() {
        // Read pump is driven externally by the poller thread.
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) {
        SteamNetworking net = networking;
        SteamID p = peer;
        if (net == null || p == null || closed) {
            pipeline().fireExceptionCaught(
                    new IllegalStateException("SteamNettyChannel.doWrite() called but channel is not connected"));
            return;
        }

        synchronized (steamLock) {
            net = networking;
            p = peer;
            if (net == null || p == null || closed) {
                return;
            }

            while (true) {
                Object msg = in.current();
                if (msg == null) {
                    break;
                }
                if (msg instanceof ByteBuf buf) {
                    if (!buf.isReadable()) {
                        in.remove();
                        continue;
                    }
                    int len = buf.readableBytes();
                    ByteBuffer direct = ByteBuffer.allocateDirect(len);
                    buf.getBytes(buf.readerIndex(), direct);
                    direct.flip();
                    try {
                        boolean ok = net.sendP2PPacket(p, direct,
                                SteamNetworking.P2PSend.ReliableWithBuffering, STEAM_CHANNEL);
                        if (!ok) {
                            LazuliMod.LOGGER.warn("[WorldHosting] sendP2PPacket returned false for peer {}",
                                    SteamNativeHandle.getNativeHandle(p));
                        }
                        in.remove();
                    } catch (Exception e) {
                        in.remove(e);
                    }
                } else {
                    in.remove(new UnsupportedOperationException(
                            "Unsupported message type: " + StringUtil.simpleClassName(msg)));
                }
            }
        }
    }

    /**
     * Drains all available P2P data packets (channel 0) and dispatches them
     * onto this channel's EventLoop. Client-side only (host uses
     * {@link SteamSession#pollPackets()}).
     */
    public void pollRead() {
        SteamNetworking net = networking;
        if (net == null || closed) {
            return;
        }
        int[] sizeBuf = new int[1];
        while (net.isP2PPacketAvailable(STEAM_CHANNEL, sizeBuf)) {
            int size = sizeBuf[0];
            if (size <= 0 || size > MAX_PACKET) {
                ByteBuffer dummy = ByteBuffer.allocateDirect(Math.max(size, 1));
                SteamID tmp = new SteamID();
                try {
                    net.readP2PPacket(tmp, dummy, STEAM_CHANNEL);
                } catch (Exception ignored) {
                    // Discard oversized/empty packet.
                }
                continue;
            }
            ByteBuffer buf = ByteBuffer.allocateDirect(size);
            SteamID sender = new SteamID();
            try {
                int read = net.readP2PPacket(sender, buf, STEAM_CHANNEL);
                if (read <= 0) {
                    break;
                }
                buf.limit(read);
                buf.position(0);
                byte[] data = new byte[read];
                buf.get(data);
                eventLoop().execute(() -> {
                    pipeline().fireChannelRead(Unpooled.wrappedBuffer(data));
                    pipeline().fireChannelReadComplete();
                });
            } catch (Exception e) {
                if (!closed) {
                    pipeline().fireExceptionCaught(e);
                }
            }
        }
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
        return !closed && peer != null && networking != null;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    /** Client-side connect handling. */
    private final class SteamUnsafe extends AbstractUnsafe {

        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }
            try {
                if (!(remoteAddress instanceof SteamAddress steamAddr)) {
                    safeSetFailure(promise, new UnsupportedOperationException(
                            "SteamNettyChannel can only connect to a SteamAddress, got: " + remoteAddress));
                    return;
                }

                SteamID remotePeer = SteamID.createFromNativeHandle(steamAddr.steamId64);
                SteamNetworking net = SteamAmbientSession.INSTANCE.getOrCreate();

                peer = remotePeer;
                networking = net;

                LazuliMod.LOGGER.info("[WorldHosting] Connecting to Steam peer {}",
                        Long.toUnsignedString(steamAddr.steamId64));

                // Register with the poller and adopt its lock.
                SteamAmbientSession.INSTANCE.setClientChannel(SteamNettyChannel.this);

                synchronized (steamLock) {
                    // Tiny handshake packet to kick off Steam NAT traversal.
                    ByteBuffer hello = ByteBuffer.allocateDirect(1);
                    hello.put((byte) 0xAB);
                    hello.flip();
                    try {
                        net.sendP2PPacket(remotePeer, hello, SteamNetworking.P2PSend.Reliable, STEAM_CHANNEL);
                    } catch (Exception e) {
                        LazuliMod.LOGGER.warn("[WorldHosting] Handshake packet failed: {}", e.getMessage());
                    }
                }

                pipeline().fireChannelActive();
                safeSetSuccess(promise);
            } catch (Throwable t) {
                safeSetFailure(promise, t);
            }
        }
    }
}
