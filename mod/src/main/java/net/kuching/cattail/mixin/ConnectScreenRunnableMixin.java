package net.kuching.cattail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.kuching.cattail.ConnectAbortState;
import net.kuching.cattail.Redirector;
import io.netty.channel.ChannelFuture;
import java.net.InetSocketAddress;
import java.util.Optional;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The connector thread of {@code ConnectScreen.connect} ("Server Connector #n", never the render
 * thread). The blocking route/helper work runs right after resolving, outside the
 * {@code synchronized (ConnectScreen.this)} block that the Cancel button (render thread) also enters;
 * inside that block {@code Connection.connect} only picks up the prepared address. The handshake still
 * uses the original host and port.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1")
abstract class ConnectScreenRunnableMixin {

    /** The enclosing screen (synthetic outer-instance field of the anonymous class). */
    @Shadow
    @Final
    ConnectScreen this$0;

    @WrapOperation(method = "run()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/resolver/ServerNameResolver;resolveAddress(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"))
    private Optional<ResolvedServerAddress> cattail$prepareRoute(ServerNameResolver resolver, ServerAddress address,
            Operation<Optional<ResolvedServerAddress>> original) {
        Optional<ResolvedServerAddress> resolved = original.call(resolver, address);
        // Cancel during DNS: skip the helper start; vanilla's next aborted check then returns
        if (!((ConnectAbortState) (Object) this$0).cattail$isAborted()) {
            resolved.ifPresent(r -> Redirector.get().prepare(r.asInetSocketAddress()));
        }
        return resolved;
    }

    @WrapOperation(method = "run()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;"))
    private ChannelFuture cattail$redirectConnect(InetSocketAddress address, boolean useEpoll, Connection connection,
            Operation<ChannelFuture> original) {
        return original.call(Redirector.get().connectAddress(address), useEpoll, connection);
    }
}
