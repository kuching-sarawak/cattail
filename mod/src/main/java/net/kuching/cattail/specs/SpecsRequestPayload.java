package net.kuching.cattail.specs;

import net.kuching.cattail.Cattail;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client at the start of the configuration phase, channel {@code cattail:specs_request}:
 * "send your {@link SysInfoPayload} now, before the play phase". Empty; the client answers only
 * if it can do so at once (see {@link SpecsClient}), else it sends after login as before.
 */
public record SpecsRequestPayload() implements CustomPacketPayload {

    public static final SpecsRequestPayload INSTANCE = new SpecsRequestPayload();

    public static final CustomPacketPayload.Type<SpecsRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Cattail.MOD_ID, "specs_request"));

    public static final StreamCodec<ByteBuf, SpecsRequestPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<SpecsRequestPayload> type() {
        return TYPE;
    }
}
