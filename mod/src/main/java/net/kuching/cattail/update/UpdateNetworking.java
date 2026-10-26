package net.kuching.cattail.update;

import net.kuching.cattail.Cattail;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Registers {@code cattail:version} (both sides; server to client only; optional, so vanilla
 * clients and servers without Cattail still connect). The server sends its version once per
 * login to a client that advertised the channel; the client shows a toast when the server is
 * newer ({@link UpdateClient}). Common code: nothing here may touch client-only classes except
 * inside the client-side handler, which only ever runs on a client.
 */
public final class UpdateNetworking {

    /** Payload protocol version; bump on any codec change. */
    static final String VERSION = "1";

    private UpdateNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).optional().playToClient(ServerVersionPayload.TYPE, ServerVersionPayload.STREAM_CODEC,
                UpdateNetworking::onClient);
    }

    /** Server thread: tell a Cattail client which version this server runs. */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.connection.hasChannel(ServerVersionPayload.TYPE)) {
            return; // vanilla or older client: never send
        }
        PacketDistributor.sendToPlayer(player, new ServerVersionPayload(ownVersion()));
    }

    /** This jar's Cattail version as NeoForge reports it, or {@code ?} outside a mod container. */
    public static String ownVersion() {
        return ModList.get().getModContainerById(Cattail.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
    }

    /** Client thread (the registrar's default for a client payload). */
    private static void onClient(ServerVersionPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) {
            UpdateClient.onServerVersion(payload.version());
        }
    }
}
