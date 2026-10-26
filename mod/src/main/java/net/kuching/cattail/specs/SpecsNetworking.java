package net.kuching.cattail.specs;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.network.Connection;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers {@code cattail:sysinfo} (both sides; client to server only; optional, so vanilla
 * clients and servers without Cattail still connect) and logs the line on the server.
 * Common code: nothing here may touch client-only classes (see {@link SpecsClient}).
 */
public final class SpecsNetworking {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpecsNetworking.class);
    /** Payload protocol version; bump on any codec change. */
    static final String VERSION = "1";

    /** Connections that already sent their specs; a client gets one line per login, never more. */
    private static final Set<Connection> SEEN = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private SpecsNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).optional().playToServer(SysInfoPayload.TYPE, SysInfoPayload.STREAM_CODEC,
                SpecsNetworking::onServer);
    }

    /** Server thread (the registrar's default): one console line per login, sanitised by {@link SysInfo#toLogLine}. */
    private static void onServer(SysInfoPayload payload, IPayloadContext context) {
        if (!SEEN.add(context.connection())) {
            return;
        }
        String player = context.player().getGameProfile().getName();
        LOGGER.info("{}", SysInfo.toLogLine(player, payload.info()));
    }
}
