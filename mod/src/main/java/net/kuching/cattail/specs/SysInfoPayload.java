package net.kuching.cattail.specs;

import net.kuching.cattail.Cattail;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client to server: the player's {@link SysInfo}, channel {@code cattail:sysinfo}. */
public record SysInfoPayload(SysInfo info) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SysInfoPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Cattail.MOD_ID, "sysinfo"));

    /** Bounded strings: the client sends at most {@link SysInfo#MAX_FIELD} chars, so 256 leaves headroom. */
    private static final StreamCodec<ByteBuf, String> STRING = ByteBufCodecs.stringUtf8(256);

    public static final StreamCodec<ByteBuf, SysInfoPayload> STREAM_CODEC = StreamCodec.of(
            SysInfoPayload::write, SysInfoPayload::read);

    @Override
    public CustomPacketPayload.Type<SysInfoPayload> type() {
        return TYPE;
    }

    private static void write(ByteBuf buf, SysInfoPayload p) {
        SysInfo i = p.info();
        STRING.encode(buf, i.cpu());
        ByteBufCodecs.VAR_INT.encode(buf, i.cores());
        ByteBufCodecs.VAR_INT.encode(buf, i.threads());
        ByteBufCodecs.VAR_LONG.encode(buf, i.ramBytes());
        STRING.encode(buf, i.gpu());
        ByteBufCodecs.VAR_LONG.encode(buf, i.vramBytes());
        STRING.encode(buf, i.driver());
        STRING.encode(buf, i.os());
        STRING.encode(buf, i.java());
        ByteBufCodecs.VAR_LONG.encode(buf, i.heapBytes());
        ByteBufCodecs.VAR_INT.encode(buf, i.renderDistance());
        STRING.encode(buf, i.shaderpack());
        ByteBufCodecs.VAR_INT.encode(buf, i.mods());
        STRING.encode(buf, i.cattailVersion());
    }

    private static SysInfoPayload read(ByteBuf buf) {
        return new SysInfoPayload(new SysInfo(
                STRING.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                STRING.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                STRING.decode(buf),
                STRING.decode(buf),
                STRING.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                STRING.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf),
                STRING.decode(buf)));
    }
}
