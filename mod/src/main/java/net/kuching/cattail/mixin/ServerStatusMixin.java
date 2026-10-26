package net.kuching.cattail.mixin;

import com.mojang.serialization.Codec;
import java.util.Optional;
import net.kuching.cattail.serverlist.CattailStatusCarrier;
import net.kuching.cattail.serverlist.ServerListInfo;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Status (server-list ping) JSON, both sides: adds the {@code cattail} object
 * ({@link ServerListInfo}) to {@link ServerStatus} in two parts.
 *
 * <p>The carrier: a mixin-added field behind {@link CattailStatusCarrier} (No Chat Reports adds one
 * to the same record), {@code volatile}: set on the server thread, read when the packet is encoded
 * on a network thread. A record's generated {@code equals}, {@code hashCode} and {@code toString}
 * use only its components, so the field changes none of them.
 *
 * <p>The codec: the static initialiser's last step replaces {@link ServerStatus#CODEC} with
 * {@link ServerListInfo#wrapStatusCodec} around the vanilla codec, before any other class can read
 * the field. Every user then goes through the wrapper: the status packet's write and read, No
 * Chat Reports, whose {@code FriendlyByteBuf.writeJsonWithCodec} and {@code readJsonWithCodec}
 * replacements check for this codec and then call it, and NeoForge's cached status JSON
 * ({@code MinecraftServer.resetStatusCache}). On NeoForge 21.1.264 the handshake listener uses the
 * two-argument status listener constructor, so that cached JSON is never sent and the packet is
 * encoded through {@code ServerStatus.CODEC} on the network thread; the wrapper covers both paths
 * either way. Cattail must not inject into those two byte-buffer methods itself: two cancelling
 * injections at their HEAD would run only one, dropping the other mod's field.
 */
@Mixin(ServerStatus.class)
abstract class ServerStatusMixin implements CattailStatusCarrier {

    @Shadow
    @Final
    @Mutable
    public static Codec<ServerStatus> CODEC;

    @Unique
    private volatile Optional<ServerListInfo> cattail$info = Optional.empty();

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void cattail$wrapCodec(CallbackInfo ci) {
        CODEC = ServerListInfo.wrapStatusCodec(CODEC);
    }

    @Override
    public Optional<ServerListInfo> cattail$info() {
        return cattail$info;
    }

    @Override
    public void cattail$setInfo(Optional<ServerListInfo> info) {
        cattail$info = info;
    }
}
