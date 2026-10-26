package net.kuching.cattail.serverlist;

import com.mojang.serialization.Codec;
import net.kuching.cattail.mixin.ServerStatusAccessor;
import net.minecraft.network.protocol.status.ServerStatus;

/**
 * Keeps {@link ServerStatus#CODEC} wrapped with the {@code cattail} entry whatever other mods did
 * to that field. {@code ServerStatusMixin} wraps it when the class loads, but another mod's
 * injection in the same static initialiser can run later and assign a fresh codec (Better
 * Compatibility Checker does, which left the status without the entry on a live server), so the
 * server re-checks just before each status is built and the client just before each ping, at no
 * cost when the field is already wrapped. Outside the mixin package on purpose: Mixin refuses to
 * load a plain class from there, while the accessor interface it calls is itself a mixin.
 */
public final class StatusCodec {

    private StatusCodec() {
    }

    /** Wraps the current {@link ServerStatus#CODEC} unless it is already Cattail's wrapper. Any thread. */
    public static void ensureWrapped() {
        Codec<ServerStatus> current = ServerStatusAccessor.cattail$codec();
        Codec<ServerStatus> wanted = ServerListInfo.ensureWrapped(current);
        if (wanted != current) {
            ServerStatusAccessor.cattail$setCodec(wanted);
        }
    }
}
