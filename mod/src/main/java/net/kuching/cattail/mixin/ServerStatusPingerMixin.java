package net.kuching.cattail.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.kuching.cattail.Redirector;
import java.net.InetSocketAddress;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.network.Connection;
import net.minecraft.util.debugchart.LocalSampleLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Server-list pings ({@code ServerSelectionList.THREAD_POOL} "Server Pinger #n" threads) also go through the helper. */
@Mixin(ServerStatusPinger.class)
abstract class ServerStatusPingerMixin {

    @WrapOperation(method = "pingServer(Lnet/minecraft/client/multiplayer/ServerData;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/Connection;connectToServer(Ljava/net/InetSocketAddress;ZLnet/minecraft/util/debugchart/LocalSampleLogger;)Lnet/minecraft/network/Connection;"))
    private Connection cattail$redirectPing(InetSocketAddress address, boolean useEpoll, LocalSampleLogger sampleLogger,
            Operation<Connection> original) {
        return original.call(Redirector.get().rewriteQuiet(address), useEpoll, sampleLogger);
    }
}
