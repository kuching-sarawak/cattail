package net.kuching.cattail.specs;

import com.mojang.blaze3d.platform.GlUtil;
import net.kuching.cattail.Cattail;
import net.kuching.cattail.config.CattailConfig;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client half of the specs feature (client-only classes; never loaded on a dedicated server).
 * Sends one {@link SysInfoPayload} per login when {@code shareSpecs} is on and the server
 * advertised {@code cattail:sysinfo}, so vanilla and older servers never see it.
 */
public final class SpecsClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpecsClient.class);
    private static final SysInfo.Hardware UNKNOWN = new SysInfo.Hardware("unknown", 0, 0, 0, List.of());

    /** OSHI's summary, read once on a daemon thread (WMI on Windows takes up to a few seconds). */
    private static volatile CompletableFuture<SysInfo.Hardware> hardware;

    private SpecsClient() {
    }

    /** Starts reading the hardware summary in the background, so the first login sends at once. */
    public static void prewarm() {
        hardware();
    }

    private static CompletableFuture<SysInfo.Hardware> hardware() {
        CompletableFuture<SysInfo.Hardware> f = hardware;
        if (f == null) {
            synchronized (SpecsClient.class) {
                f = hardware;
                if (f == null) {
                    CompletableFuture<SysInfo.Hardware> created = new CompletableFuture<>();
                    Thread t = new Thread(() -> {
                        try {
                            created.complete(SysInfo.readHardware());
                        } catch (Throwable e) { // OSHI/JNA errors must never break a login
                            LOGGER.warn("Cattail could not read the hardware summary: {}", e.toString());
                            created.complete(UNKNOWN);
                        }
                    }, "cattail-sysinfo");
                    t.setDaemon(true);
                    t.start();
                    hardware = f = created;
                }
            }
        }
        return f;
    }

    /** Render thread. Reads the game-side values here (GL must be called on this thread), sends once OSHI is done. */
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!CattailConfig.SHARE_SPECS.get()) {
            return;
        }
        ClientPacketListener listener = event.getPlayer().connection;
        if (!listener.hasChannel(SysInfoPayload.TYPE)) {
            return; // vanilla or older server: never send
        }
        Minecraft mc = Minecraft.getInstance();
        String renderer = GlUtil.getRenderer();
        int renderDistance = mc.options.renderDistance().get();
        ModList mods = ModList.get();
        Optional<String> shaderpack = SysInfo.irisShaderpack(FMLPaths.GAMEDIR.get(), mods.isLoaded("iris"));
        int modCount = mods.size();
        String version = mods.getModContainerById(Cattail.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        long maxHeap = Runtime.getRuntime().maxMemory();
        hardware().thenAccept(hw -> {
            SysInfo info = SysInfo.collect(hw, renderer, System.getProperties(), maxHeap, renderDistance, shaderpack,
                    modCount, version);
            mc.execute(() -> {
                if (mc.getConnection() == listener && listener.hasChannel(SysInfoPayload.TYPE)) {
                    listener.send(new SysInfoPayload(info));
                    LOGGER.info("Cattail sent PC specs to the server");
                }
            });
        });
    }
}
