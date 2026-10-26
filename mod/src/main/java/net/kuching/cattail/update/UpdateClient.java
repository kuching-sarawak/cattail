package net.kuching.cattail.update;

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client half of the update notice (client-only classes; never loaded on a dedicated server).
 * Shows one toast, top right as every system toast, per server per game run when the server's
 * Cattail is newer than this jar.
 */
public final class UpdateClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateClient.class);
    /** Servers (by address, or the world name in singleplayer) already told about this run. */
    private static final Set<String> SHOWN = Collections.synchronizedSet(new HashSet<>());

    private UpdateClient() {
    }

    /** Client thread. */
    public static void onServerVersion(String serverVersion) {
        Minecraft mc = Minecraft.getInstance();
        ServerData server = mc.getCurrentServer();
        String key = server != null ? server.ip : "singleplayer";
        String own = UpdateNetworking.ownVersion();
        Optional<String> notice = UpdateNotice.decide(serverVersion, own, key, SHOWN);
        if (notice.isEmpty()) {
            return;
        }
        LOGGER.info("Cattail update: the server runs {}, this jar is {}", serverVersion, own);
        mc.getToasts().addToast(SystemToast.multiline(mc, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal(UpdateNotice.TITLE), Component.literal(notice.get())));
    }
}
