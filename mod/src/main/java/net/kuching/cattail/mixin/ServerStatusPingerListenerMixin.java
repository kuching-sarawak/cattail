package net.kuching.cattail.mixin;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kuching.cattail.serverlist.CattailStatusCarrier;
import net.kuching.cattail.serverlist.ServerListInfo;
import net.kuching.cattail.serverlist.StatusCodec;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The status listener of a server-list ping (the anonymous class in
 * {@code ServerStatusPinger.pingServer}): copies the {@code cattail} object of the status response
 * onto the row's {@link ServerData}, empty when the response has none, as vanilla copies the MOTD
 * and player count. The copy runs just before vanilla reads the description, which is past its
 * check for a second, unrequested response (that one only disconnects), so such a response never
 * overwrites the info. The row's {@code ServerData} is captured from the constructor's
 * parameters, not from the class's synthetic fields, whose names are a compiler detail.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ServerStatusPinger$1")
abstract class ServerStatusPingerListenerMixin {

    @Unique
    private static final Logger CATTAIL_LOGGER = LoggerFactory.getLogger(ServerStatusPingerListenerMixin.class);
    @Unique
    private static final AtomicBoolean CATTAIL_WARNED = new AtomicBoolean();

    @Unique
    private ServerData cattail$serverData;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cattail$captureServerData(ServerStatusPinger pinger, Connection connection, ServerData serverData,
            Runnable onIconChanged, Runnable onPinged, InetSocketAddress resolved, ServerAddress address, CallbackInfo ci) {
        cattail$serverData = serverData;
        StatusCodec.ensureWrapped(); // before this ping's response is decoded on the network thread
    }

    @Inject(method = "handleStatusResponse(Lnet/minecraft/network/protocol/status/ClientboundStatusResponsePacket;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/status/ServerStatus;description()Lnet/minecraft/network/chat/Component;"))
    private void cattail$copyInfo(ClientboundStatusResponsePacket packet, CallbackInfo ci) {
        if (cattail$serverData == null) {
            if (CATTAIL_WARNED.compareAndSet(false, true)) {
                CATTAIL_LOGGER.warn("Cattail: a server-list ping has no row to show its Cattail icon on");
            }
            return;
        }
        ((CattailStatusCarrier) cattail$serverData).cattail$setInfo(ServerListInfo.of(packet.status()));
    }
}
