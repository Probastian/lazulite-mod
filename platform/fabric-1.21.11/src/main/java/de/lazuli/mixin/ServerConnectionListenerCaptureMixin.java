package de.lazuli.mixin;

import de.lazuli.worldhosting.WorldHostingHookHolder;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;

import net.minecraft.server.ServerNetworkIo;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetAddress;

/**
 * 1.21.11 (Yarn-mapped) variant capturing Minecraft's own Netty
 * {@code childHandler}/{@code EventLoopGroup} during
 * {@code ServerNetworkIo.bind} (Yarn name for {@code ServerConnectionListener.startTcpServerListener})
 * and stopping hosting on {@code stop}. Ported from the prototype's
 * {@code ServerConnectionListenerMixin}.
 *
 * <p>Both {@code ServerBootstrap.childHandler(ChannelHandler)} and
 * {@code ServerBootstrap.group(EventLoopGroup)} INVOKE targets confirmed present
 * inside {@code bind} via {@code javap -c} against the resolved Yarn jar.
 */
@Mixin(ServerNetworkIo.class)
public abstract class ServerConnectionListenerCaptureMixin {

    @Unique
    private ChannelHandler lazuli$childHandler;
    @Unique
    private EventLoopGroup lazuli$group;

    @ModifyArg(
            method = "bind",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/ServerBootstrap;childHandler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/ServerBootstrap;",
                    remap = false))
    private ChannelHandler lazuli$captureChildHandler(ChannelHandler childHandler) {
        lazuli$childHandler = childHandler;
        return childHandler;
    }

    @ModifyArg(
            method = "bind",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/ServerBootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/ServerBootstrap;",
                    remap = false))
    private EventLoopGroup lazuli$captureGroup(EventLoopGroup group) {
        lazuli$group = group;
        return group;
    }

    @Inject(method = "bind", at = @At("TAIL"))
    private void lazuli$onBound(InetAddress address, int port, CallbackInfo ci) {
        if (lazuli$childHandler != null && lazuli$group != null) {
            WorldHostingHookHolder.storeNettyArgs(lazuli$childHandler, lazuli$group);
            lazuli$childHandler = null;
            lazuli$group = null;
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void lazuli$onStop(CallbackInfo ci) {
        WorldHostingHookHolder.onWorldStop();
    }
}
