package de.lazuli.mixin;

import de.lazuli.worldhosting.SteamAddress;
import de.lazuli.worldhosting.SteamAmbientSession;
import de.lazuli.worldhosting.SteamNettyChannel;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.crypto.Cipher;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Redirects Minecraft's own client-connect Netty bootstrap onto a Steam P2P
 * channel (FR3.2): when {@link SteamAmbientSession} has a pending Steam target,
 * swaps the {@code NioSocketChannel} class for {@link SteamNettyChannel}, the
 * event-loop group for the ambient session's own, and the final
 * {@code Bootstrap.connect(host, port)} for a connect to the {@link SteamAddress}.
 * Also disables Minecraft's own double-encryption for Steam channels (the
 * transport is already Steam-encrypted). Ported from the prototype's
 * {@code ConnectionMixin} (address-smuggling replaced by the ambient session's
 * pending-connect, since v1 does not touch Direct Connect).
 *
 * <p>Target confirmed via {@code javap} against the resolved 26.2 jar:
 * {@code Connection#connect(InetSocketAddress, EventLoopGroupHolder, Connection)}
 * with inner {@code Bootstrap.channel(Class)}/{@code group(EventLoopGroup)}/
 * {@code connect(InetAddress, int)} calls; {@code Connection.channel} field;
 * {@code setEncryptionKey(Cipher, Cipher)}.
 */
@Mixin(Connection.class)
public abstract class ConnectionSteamChannelMixin {

    @Shadow
    @Final
    private io.netty.channel.Channel channel;

    @Unique
    private static SteamAddress lazuli$pending = null;

    @Inject(method = "connect", at = @At("HEAD"))
    private static void lazuli$hijackStart(InetSocketAddress address, @Coerce Object groupHolder,
            Connection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        SteamAddress pending = SteamAmbientSession.INSTANCE.consumePendingConnect();
        if (pending != null) {
            lazuli$pending = pending;
            SteamAmbientSession.INSTANCE.setDisconnectCallback(reason ->
                    Minecraft.getInstance().execute(() -> {
                        connection.disconnect(reason);
                        connection.handleDisconnection();
                    }));
        }
    }

    @ModifyArg(method = "connect",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;",
                    remap = false))
    private static Class<?> lazuli$hijackChannel(Class<?> clazz) {
        return lazuli$pending != null ? SteamNettyChannel.class : clazz;
    }

    @ModifyArg(method = "connect",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;",
                    remap = false))
    private static EventLoopGroup lazuli$hijackGroup(EventLoopGroup group) {
        return lazuli$pending != null ? SteamAmbientSession.INSTANCE.group : group;
    }

    @Redirect(method = "connect",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;",
                    remap = false))
    private static ChannelFuture lazuli$hijackConnect(Bootstrap instance, InetAddress host, int port) {
        if (lazuli$pending != null) {
            SteamAddress addr = lazuli$pending;
            lazuli$pending = null;
            return instance.connect(addr);
        }
        return instance.connect(host, port);
    }

    @Inject(method = "setEncryptionKey", at = @At("HEAD"), cancellable = true)
    private void lazuli$killDoubleEncryption(Cipher enc, Cipher dec, CallbackInfo ci) {
        Channel ch = channel;
        if (ch instanceof SteamNettyChannel) {
            ci.cancel();
        }
    }
}
