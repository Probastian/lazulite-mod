package de.lazuli.worldhosting;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamNetworking;
import com.codedisaster.steamworks.SteamNetworkingCallback;

import de.lazuli.LazuliMod;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Owns the client-side Steam P2P networking instance, its outgoing
 * {@code EventLoopGroup}, and the single "connect to this Steam host" operation
 * (FR3.1) that both join paths funnel through: the native overlay "Join Game"
 * callback and the Friends Sidebar "Join game" action. The connect goes through
 * vanilla's own {@code ConnectScreen}/{@code Connection.connect} flow, so all
 * normal connecting UI behaves identically to a normal server connect (FR3.2),
 * and a clean disconnect reason is surfaced on host-close (FR3.3). Ported from
 * the prototype's own {@code SteamAmbientSession} + {@code connectToSteamPeer}.
 *
 * <p>26.2 (Mojang-mapped) variant: uses {@code Component}, {@code Minecraft},
 * {@code ConnectScreen.startConnecting(...)}, {@code ServerData}.
 */
public final class SteamAmbientSession {

    public static final SteamAmbientSession INSTANCE = new SteamAmbientSession();

    @SuppressWarnings("deprecation")
    public final EventLoopGroup group = new DefaultEventLoopGroup(1);

    private volatile SteamNetworking networking;
    private volatile SteamNettyChannel clientChannel;
    private Thread pollerThread;

    /** Guards every native SteamNetworking call on the client side. */
    public final Object steamLock = new Object();

    /** The Steam target for the next connect, consumed by the Connection mixin. */
    private volatile SteamAddress pendingConnect = null;

    private volatile Consumer<Component> disconnectCallback = null;
    private volatile Component pendingDisconnectReason = null;

    private SteamAmbientSession() {
    }

    /**
     * Initiates a connect to the given Steam host via vanilla's own connect
     * flow (FR3.1/FR3.2). Safe to call from any thread.
     *
     * @param hostSteamId64 the host's own {@code SteamID64}
     */
    public void connectToSteamPeer(long hostSteamId64) {
        pendingConnect = new SteamAddress(hostSteamId64);
        Minecraft mc = Minecraft.getInstance();
        // A loopback placeholder address: it resolves without DNS so vanilla's
        // connect flow runs normally; the Connection mixin substitutes the
        // SteamAddress before the real bootstrap connect happens.
        String placeholder = "127.0.0.1";
        String name = "Steam Friend";
        mc.execute(() -> {
            LazuliMod.LOGGER.info("[WorldHosting] Joining Steam host {}", Long.toUnsignedString(hostSteamId64));
            ServerData data = new ServerData(name, placeholder, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(placeholder), data, false, null);
        });
    }

    /** @return and clears the pending Steam target set by {@link #connectToSteamPeer}. */
    public SteamAddress consumePendingConnect() {
        SteamAddress pending = pendingConnect;
        pendingConnect = null;
        return pending;
    }

    /** Returns the client-side {@link SteamNetworking}, creating it lazily. */
    public synchronized SteamNetworking getOrCreate() {
        if (networking == null) {
            networking = new SteamNetworking(new SteamNetworkingCallback() {
                @Override
                public void onP2PSessionRequest(SteamID remotePeer) {
                    LazuliMod.LOGGER.warn("[WorldHosting] Unexpected inbound P2P session request on client side");
                }

                @Override
                public void onP2PSessionConnectFail(SteamID remotePeer, SteamNetworking.P2PSessionError error) {
                    LazuliMod.LOGGER.warn("[WorldHosting] P2P connect fail: {}", error);
                    triggerDisconnect(Component.translatable("lazuli.worldhosting.disconnect.steam_error", error.toString()));
                }
            });

            pollerThread = new Thread(this::pollerLoop, "lazuli-worldhosting-client-poller");
            pollerThread.setDaemon(true);
            pollerThread.start();
        }
        return networking;
    }

    /** Registers the connection's clean-disconnect callback (from the Connection mixin). */
    public void setDisconnectCallback(Consumer<Component> callback) {
        Component pending = pendingDisconnectReason;
        if (pending != null) {
            pendingDisconnectReason = null;
            callback.accept(pending);
        } else {
            disconnectCallback = callback;
        }
    }

    private void triggerDisconnect(Component reason) {
        Consumer<Component> cb = disconnectCallback;
        if (cb != null) {
            disconnectCallback = null;
            cb.accept(reason);
        } else {
            pendingDisconnectReason = reason;
        }
    }

    /** Registers the outgoing channel with the poller and shares the lock. */
    public void setClientChannel(SteamNettyChannel channel) {
        channel.steamLock = steamLock;
        this.clientChannel = channel;
    }

    private void pollerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                SteamNetworking net = networking;
                SteamNettyChannel ch = clientChannel;
                if (ch != null) {
                    if (!ch.isOpen()) {
                        clientChannel = null;
                    } else {
                        synchronized (steamLock) {
                            ch.pollRead();
                            if (net != null) {
                                SteamID peer = ch.peer;
                                long expectedId = peer != null ? SteamNativeHandle.getNativeHandle(peer) : 0L;
                                SteamDisconnectProtocol.FinResult fin =
                                        SteamDisconnectProtocol.pollFinWithReason(net, expectedId);
                                if (fin.received()) {
                                    String reasonKey = fin.reasonKey();
                                    Component reason = reasonKey != null
                                            ? Component.translatable(reasonKey)
                                            : Component.translatable("lazuli.worldhosting.disconnect.host_closed");
                                    clientChannel = null;
                                    triggerDisconnect(reason);
                                }
                            }
                        }
                    }
                }
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LazuliMod.LOGGER.error("[WorldHosting] client pollerLoop error", e);
            }
        }
    }
}
