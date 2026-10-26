package net.kuching.cattail.specs;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the specs channels (both sides; optional, so vanilla clients and servers without
 * Cattail still connect) and logs the line on the server.
 *
 * <p>{@code cattail:sysinfo} goes client to server in the configuration or the play phase;
 * {@code cattail:specs_request} goes server to client in the configuration phase only, sent by
 * {@link #onConfigurationStart} to a client that advertised it. An older client never sees the
 * request and sends after login as before; a newer client against an older server gets no
 * request and does the same.
 *
 * <p>Common code: nothing here may touch client-only classes. The client installs its handler
 * for the request through {@link #onClientRequest}.
 */
public final class SpecsNetworking {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpecsNetworking.class);
    /** Payload protocol version; bump on any codec change. */
    static final String VERSION = "1";

    /** Connections that already sent their specs; a client gets one line per login, never more. */
    private static final Set<Connection> SEEN = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** The client's handler for a request, installed by the client at start-up; a no-op elsewhere. */
    private static volatile Consumer<IPayloadContext> onClientRequest = context -> { };

    private SpecsNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).optional()
                .commonToServer(SysInfoPayload.TYPE, SysInfoPayload.STREAM_CODEC, SpecsNetworking::onServer)
                .configurationToClient(SpecsRequestPayload.TYPE, SpecsRequestPayload.STREAM_CODEC,
                        (payload, context) -> onClientRequest.accept(context));
    }

    /** Client start-up: the code that answers a request (runs on the client's main thread). */
    public static void installClientRequestHandler(Consumer<IPayloadContext> handler) {
        onClientRequest = handler;
    }

    /**
     * Server, start of the configuration phase: ask a client that has the request channel;
     * nothing for the rest. The event is NeoForge's first hook after channel negotiation and
     * fires before any configuration task runs, so the request is sent directly here rather than
     * queued as a task: a task would wait behind the registry sync (seconds on a large modpack),
     * while a direct send reaches the client first and its answer comes back while the sync is
     * still under way. The login never waits for the answer.
     */
    public static void onConfigurationStart(RegisterConfigurationTasksEvent event) {
        if (event.getListener().hasChannel(SpecsRequestPayload.TYPE)) {
            event.getListener().send(SpecsRequestPayload.INSTANCE);
        }
    }

    /**
     * Server thread (the registrar's default), configuration or play phase: one console line per
     * login, sanitised by {@link SysInfo#toLogLine}. The name comes from the listener's profile,
     * the same object in both phases ({@code context.player()} does not exist in configuration),
     * and the address from the connection.
     */
    private static void onServer(SysInfoPayload payload, IPayloadContext context) {
        if (!SEEN.add(context.connection())) {
            return;
        }
        String player = ((ServerCommonPacketListenerImpl) context.listener()).getOwner().getName();
        String ip = SysInfo.hostOf(context.connection().getRemoteAddress());
        LOGGER.info("{}", SysInfo.toLogLine(player, payload.info(), ip));
    }
}
