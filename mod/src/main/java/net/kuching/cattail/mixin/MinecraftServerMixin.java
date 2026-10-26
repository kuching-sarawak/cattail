package net.kuching.cattail.mixin;

import net.kuching.cattail.serverlist.CattailStatusCarrier;
import net.kuching.cattail.serverlist.ServerListInfoSource;
import net.kuching.cattail.serverlist.StatusCodec;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Both sides, acting only where a source is installed (a dedicated server): each status the server
 * builds (at start and every 5 s) carries {@link ServerListInfoSource#current}, which the status
 * packet writes as its {@code cattail} object when it is encoded through the wrapped
 * {@code ServerStatus.CODEC} on a network thread (on 21.1.264 the handshake listener does not send
 * NeoForge's cached status JSON). Set every time, so empty clears it.
 */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {

    @Inject(method = "buildServerStatus", at = @At("RETURN"))
    private void cattail$addServerListInfo(CallbackInfoReturnable<ServerStatus> cir) {
        StatusCodec.ensureWrapped(); // before NeoForge encodes this status (resetStatusCache follows at once)
        ((CattailStatusCarrier) (Object) cir.getReturnValue()).cattail$setInfo(ServerListInfoSource.current());
    }
}
