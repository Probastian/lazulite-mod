package de.lazuli.mixin;

import de.lazuli.worldhosting.WorldHostingHookHolder;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;

import net.minecraft.server.network.ServerConnectionListener;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetAddress;

/**
 * Captures Minecraft's own Netty {@code childHandler} + {@code EventLoopGroup}
 * every time {@code startTcpServerListener} runs (triggered on world load by
 * {@link IntegratedServerWorldHostingMixin}) and stores them in
 * {@link WorldHostingHookHolder}, so a Steam P2P session can reuse the exact
 * same server pipeline object graph. Also stops hosting when the listener stops.
 * Ported from the prototype's {@code ServerConnectionListenerMixin}.
 *
 * <p>Both {@code ServerBootstrap.childHandler(ChannelHandler)} and
 * {@code ServerBootstrap.group(EventLoopGroup)} INVOKE targets confirmed present
 * inside {@code startTcpServerListener} via {@code javap -c} against the
 * resolved 26.2 jar.
 */
@Mixin(ServerConnectionListener.class)
public abstract class ServerConnectionListenerCaptureMixin {

    @Unique
    private ChannelHandler lazuli$childHandler;
    @Unique
    private EventLoopGroup lazuli$group;

    @ModifyArg(
            method = "startTcpServerListener",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/ServerBootstrap;childHandler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/ServerBootstrap;",
                    remap = false))
    private ChannelHandler lazuli$captureChildHandler(ChannelHandler childHandler) {
        lazuli$childHandler = childHandler;
        return childHandler;
    }

    @ModifyArg(
            method = "startTcpServerListener",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/ServerBootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/ServerBootstrap;",
                    remap = false))
    private EventLoopGroup lazuli$captureGroup(EventLoopGroup group) {
        lazuli$group = group;
        return group;
    }

    @Inject(method = "startTcpServerListener", at = @At("TAIL"))
    private void lazuli$onTcpListenerStarted(InetAddress address, int port, CallbackInfo ci) {
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
