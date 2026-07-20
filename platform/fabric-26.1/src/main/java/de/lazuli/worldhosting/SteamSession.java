package de.lazuli.worldhosting;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamNetworking;
import com.codedisaster.steamworks.SteamNetworkingCallback;

import de.lazuli.LazuliMod;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;

import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongPredicate;

/**
 * Host-side Steam P2P session. Given Minecraft's own captured Netty
 * {@code childHandler}/{@code EventLoopGroup} (from
 * {@code ServerConnectionListener}'s pipeline), binds a
 * {@link SteamServerChannel} and registers a {@link SteamNetworkingCallback}
 * for inbound peer connections; a dedicated poller thread drains P2P reads and
 * dispatches them to the correct child channel's EventLoop. Ported from the
 * prototype's own {@code SteamSession} (FoF/policy-broadcast removed).
 */
public final class SteamSession {

    public enum State { STARTING, STARTED, STOPPING, STOPPED }

    public volatile State state = State.STARTING;

    private final ChannelHandler childHandler;
    private final EventLoopGroup group;
    private final LongPredicate canJoin;

    private SteamNetworking networking;
    private SteamServerChannel serverChannel;

    final CopyOnWriteArrayList<SteamNettyChannel> children = new CopyOnWriteArrayList<>();

    /** Guards every native SteamNetworking call on the host side. */
    final Object steamLock = new Object();

    private final int[] sizeBuf = new int[1];
    private final SteamID senderBuf = new SteamID();

    private Thread pollerThread;

    public SteamSession(ChannelHandler childHandler, EventLoopGroup group, LongPredicate canJoin) {
        this.childHandler = childHandler;
        this.group = group;
        this.canJoin = canJoin;
    }

    /** @return {@code true} if at least one remote peer is currently connected (FR1.4). */
    public boolean hasConnectedPeers() {
        return !children.isEmpty();
    }

    public void startAsync() {
        Thread t = new Thread(this::start, "lazuli-worldhosting-session-init");
        t.setDaemon(true);
        t.start();
    }

    private void start() {
        try {
            networking = new SteamNetworking(new SteamNetworkingCallback() {
                @Override
                public void onP2PSessionRequest(SteamID remotePeer) {
                    long id = SteamNativeHandle.getNativeHandle(remotePeer);
                    LazuliMod.LOGGER.info("[WorldHosting] P2P session request from {}", Long.toUnsignedString(id));
                    // Drop any stale channel for a re-connecting peer.
                    children.removeIf(ch -> {
                        SteamID p = ch.peer;
                        if (p != null && SteamNativeHandle.getNativeHandle(p) == id) {
                            if (ch.isOpen()) {
                                ch.close();
                            }
                            return true;
                        }
                        return false;
                    });
                    if (serverChannel != null) {
                        serverChannel.acceptPeer(remotePeer);
                    }
                }

                @Override
                public void onP2PSessionConnectFail(SteamID remotePeer, SteamNetworking.P2PSessionError error) {
                    long id = SteamNativeHandle.getNativeHandle(remotePeer);
                    LazuliMod.LOGGER.warn("[WorldHosting] P2P connect fail for peer {} -- {}", id, error);
                    SteamNetworking net = networking;
                    if (net != null) {
                        net.closeP2PSessionWithUser(remotePeer);
                    }
                    children.removeIf(ch -> {
                        SteamID p = ch.peer;
                        if (p != null && SteamNativeHandle.getNativeHandle(p) == id) {
                            if (ch.isOpen()) {
                                ch.close();
                            }
                            return true;
                        }
                        return false;
                    });
                }
            });

            serverChannel = new SteamServerChannel(this, networking, canJoin);

            new ServerBootstrap()
                    .group(group)
                    .channelFactory(() -> serverChannel)
                    .childHandler(childHandler)
                    .bind(new SteamAddress(0L))
                    .addListener((ChannelFuture f) -> {
                        if (f.isSuccess()) {
                            state = State.STARTED;
                            LazuliMod.LOGGER.info("[WorldHosting] Listening for Steam P2P connections.");
                        } else {
                            state = State.STOPPED;
                            LazuliMod.LOGGER.error("[WorldHosting] Bind failed: {}",
                                    f.cause() != null ? f.cause().getMessage() : "unknown");
                        }
                    });

            pollerThread = new Thread(this::pollerLoop, "lazuli-worldhosting-packet-poller");
            pollerThread.setDaemon(true);
            pollerThread.start();
        } catch (Throwable e) {
            state = State.STOPPED;
            LazuliMod.LOGGER.error("[WorldHosting] Failed to start Steam session", e);
        }
    }

    public void stop() {
        state = State.STOPPING;
        LazuliMod.LOGGER.info("[WorldHosting] Stopping Steam session...");

        synchronized (steamLock) {
            SteamNetworking net = networking;
            if (net != null) {
                for (SteamNettyChannel ch : children) {
                    SteamID p = ch.peer;
                    if (p != null) {
                        SteamDisconnectProtocol.sendFin(net, p, "lazuli.worldhosting.disconnect.host_closed");
                    }
                }
            }
        }

        try {
            Thread.sleep(150);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        synchronized (steamLock) {
            for (SteamNettyChannel ch : children) {
                ch.finAlreadySent = true;
                if (ch.isOpen()) {
                    ch.close();
                }
            }
            children.clear();

            if (serverChannel != null) {
                serverChannel.close();
                serverChannel = null;
            }
            if (networking != null) {
                networking.dispose();
                networking = null;
            }
        }

        if (pollerThread != null) {
            pollerThread.interrupt();
            pollerThread = null;
        }
        state = State.STOPPED;
    }

    private void pollerLoop() {
        while (!Thread.currentThread().isInterrupted()
                && state != State.STOPPING && state != State.STOPPED) {
            try {
                pollPackets();
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LazuliMod.LOGGER.error("[WorldHosting] pollerLoop error", e);
            }
        }
    }

    private void pollPackets() {
        SteamNetworking net = networking;
        if (net == null) {
            return;
        }
        synchronized (steamLock) {
            net = networking;
            if (net == null) {
                return;
            }

            // Disconnect channel (2): explicit FIN from clients.
            int[] dcSize = new int[1];
            while (net.isP2PPacketAvailable(SteamDisconnectProtocol.DISCONNECT_CHANNEL, dcSize)) {
                ByteBuffer dcBuf = ByteBuffer.allocateDirect(Math.max(dcSize[0], 1));
                SteamID dcSender = new SteamID();
                try {
                    int read = net.readP2PPacket(dcSender, dcBuf, SteamDisconnectProtocol.DISCONNECT_CHANNEL);
                    if (read == 1 && dcBuf.get(0) == SteamDisconnectProtocol.FIN) {
                        long peerId = SteamNativeHandle.getNativeHandle(dcSender);
                        children.removeIf(ch -> {
                            SteamID p = ch.peer;
                            if (p != null && SteamNativeHandle.getNativeHandle(p) == peerId) {
                                if (ch.isOpen()) {
                                    ch.close();
                                }
                                return true;
                            }
                            return false;
                        });
                    }
                } catch (Exception e) {
                    LazuliMod.LOGGER.warn("[WorldHosting] disconnect channel error: {}", e.getMessage());
                }
            }

            // Data channel (0).
            while (net.isP2PPacketAvailable(SteamNettyChannel.STEAM_CHANNEL, sizeBuf)) {
                int size = sizeBuf[0];
                if (size <= 0 || size > SteamNettyChannel.MAX_PACKET) {
                    ByteBuffer dummy = ByteBuffer.allocateDirect(Math.max(size, 1));
                    try {
                        net.readP2PPacket(senderBuf, dummy, SteamNettyChannel.STEAM_CHANNEL);
                    } catch (Exception ignored) {
                        // Discard.
                    }
                    continue;
                }
                ByteBuffer buf = ByteBuffer.allocateDirect(size);
                try {
                    int read = net.readP2PPacket(senderBuf, buf, SteamNettyChannel.STEAM_CHANNEL);
                    if (read <= 0) {
                        break;
                    }
                    long peerId = SteamNativeHandle.getNativeHandle(senderBuf);
                    if (read == 1 && buf.get(0) == (byte) 0xAB) {
                        continue; // handshake sentinel
                    }
                    buf.limit(read);
                    buf.position(0);
                    byte[] data = new byte[read];
                    buf.get(data);

                    for (SteamNettyChannel ch : children) {
                        SteamID p = ch.peer;
                        if (p != null && SteamNativeHandle.getNativeHandle(p) == peerId) {
                            ch.eventLoop().execute(() -> {
                                ch.pipeline().fireChannelRead(Unpooled.wrappedBuffer(data));
                                ch.pipeline().fireChannelReadComplete();
                            });
                            break;
                        }
                    }
                } catch (Exception e) {
                    LazuliMod.LOGGER.error("[WorldHosting] pollPackets error", e);
                }
            }
        }

        children.removeIf(ch -> {
            if (!ch.isOpen() || !ch.isActive()) {
                if (ch.isOpen()) {
                    ch.close();
                }
                return true;
            }
            return false;
        });
    }

    void registerChild(SteamNettyChannel child) {
        children.add(child);
    }
}
