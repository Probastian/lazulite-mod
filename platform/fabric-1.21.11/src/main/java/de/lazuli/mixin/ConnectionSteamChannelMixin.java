package de.lazuli.mixin;

import de.lazuli.worldhosting.SteamAddress;
import de.lazuli.worldhosting.SteamAmbientSession;
import de.lazuli.worldhosting.SteamNettyChannel;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;

import org.spongepowered.asm.mixin.Mixin;
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
 * 1.21.11 (Yarn-mapped) variant of the client-connect Netty bootstrap hijack +
 * double-encryption disable (FR3.2). Ported from the prototype's
 * {@code ConnectionMixin}.
 *
 * <p>Target confirmed via {@code javap} against the resolved Yarn jar:
 * {@code ClientConnection#connect(InetSocketAddress, NetworkingBackend, ClientConnection)}
 * (the static bootstrap builder overload, disambiguated by full descriptor) with
 * inner {@code Bootstrap.channel(Class)}/{@code group(EventLoopGroup)}/
 * {@code connect(InetAddress, int)} calls; {@code ClientConnection.getAddress()}
 * (Yarn name for {@code getRemoteAddress()}); {@code setupEncryption(Cipher, Cipher)}
 * (Yarn name for {@code setEncryptionKey}).
 */
@Mixin(ClientConnection.class)
public abstract class ConnectionSteamChannelMixin {

    @Unique
    private static SteamAddress lazuli$pending = null;

    @Inject(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/network/NetworkingBackend;Lnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;",
            at = @At("HEAD"))
    private static void lazuli$hijackStart(InetSocketAddress address, @Coerce Object backend,
            ClientConnection connection, CallbackInfoReturnable<ChannelFuture> cir) {
        SteamAddress pending = SteamAmbientSession.INSTANCE.consumePendingConnect();
        if (pending != null) {
            lazuli$pending = pending;
            SteamAmbientSession.INSTANCE.setDisconnectCallback(reason ->
                    MinecraftClient.getInstance().execute(() -> {
                        connection.disconnect(reason);
                        connection.handleDisconnection();
                    }));
        }
    }

    @ModifyArg(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/network/NetworkingBackend;Lnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;",
                    remap = false))
    private static Class<?> lazuli$hijackChannel(Class<?> clazz) {
        return lazuli$pending != null ? SteamNettyChannel.class : clazz;
    }

    @ModifyArg(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/network/NetworkingBackend;Lnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;",
                    remap = false))
    private static EventLoopGroup lazuli$hijackGroup(EventLoopGroup group) {
        return lazuli$pending != null ? SteamAmbientSession.INSTANCE.group : group;
    }

    @Redirect(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/network/NetworkingBackend;Lnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;",
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

    @Inject(method = "setupEncryption", at = @At("HEAD"), cancellable = true)
    private void lazuli$killDoubleEncryption(Cipher decrypter, Cipher encrypter, CallbackInfo ci) {
        if (((ClientConnection) (Object) this).getAddress() instanceof SteamAddress) {
            ci.cancel();
        }
    }
}
