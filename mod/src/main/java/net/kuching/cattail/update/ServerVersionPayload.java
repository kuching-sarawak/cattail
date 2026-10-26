package net.kuching.cattail.update;

import io.netty.buffer.ByteBuf;
import net.kuching.cattail.Cattail;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client, once per login: the server's Cattail version, channel {@code cattail:version}. */
public record ServerVersionPayload(String version) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerVersionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Cattail.MOD_ID, "version"));

    /** A mod version is short; 64 bounds what a server can send. */
    public static final StreamCodec<ByteBuf, ServerVersionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), ServerVersionPayload::version, ServerVersionPayload::new);

    @Override
    public CustomPacketPayload.Type<ServerVersionPayload> type() {
        return TYPE;
    }
}
