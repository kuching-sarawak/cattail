package net.kuching.cattail.specs;

import com.mojang.blaze3d.platform.GlUtil;
import net.kuching.cattail.Cattail;
import net.kuching.cattail.config.CattailConfig;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client half of the specs feature (client-only classes; never loaded on a dedicated server).
 * Sends one {@link SysInfoPayload} per login when {@code shareSpecs} is on and the server
 * advertised {@code cattail:sysinfo}, so vanilla and older servers never see it.
 *
 * <p>Two ways to send, one line per connection either way. A server that asks during the
 * configuration phase ({@link SpecsRequestPayload}) gets the answer right there, synchronously on
 * the main thread, when the hardware summary is already known (it was started when the config
 * was applied, so that is the normal case): the reply then precedes the client's own end of
 * configuration on the wire, which keeps it a configuration-phase packet. Otherwise, and against
 * a server that does not ask, the login sends it: the render thread reads the two values only it
 * may read (the GL renderer, cached after the first login, and the render distance) and hands
 * the rest, the Iris config file, the string work and the send, to the game's IO pool through
 * {@link SpecsDispatch}, so the specs leave within milliseconds of the login whatever the render
 * thread is busy with.
 */
public final class SpecsClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpecsClient.class);

    /** OSHI's summary, read once per game process (WMI on Windows takes up to a few seconds). */
    private static volatile CompletableFuture<SysInfo.Hardware> hardware;
    /** Values fixed for the life of the game process, read once on the first use. */
    private static volatile Session session;
    /** The connection whose specs went out in the configuration phase, so the login does not resend. */
    private static volatile WeakReference<Connection> answered = new WeakReference<>(null);

    /**
     * @param renderer   the GL renderer string (GL, so read on the render thread)
     * @param irisLoaded whether Iris is loaded (the mod list is immutable after loading)
     */
    private record Session(String renderer, boolean irisLoaded, int modCount, String version, Path gameDir) {}

    private SpecsClient() {
    }

    /** Client start-up: wires the configuration-phase request to {@link #onRequest}. */
    public static void install() {
        SpecsNetworking.installClientRequestHandler(SpecsClient::onRequest);
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
                    hardware = f = SpecsDispatch.readAsync(SysInfo::readHardware, SpecsDispatch.READ_TIMEOUT, LOGGER);
                }
            }
        }
        return f;
    }

    /** Render thread: the GL string must be read here; the rest never changes while the game runs. */
    private static Session session() {
        Session s = session;
        if (s == null) {
            ModList mods = ModList.get();
            s = new Session(GlUtil.getRenderer(), mods.isLoaded("iris"), mods.size(),
                    mods.getModContainerById(Cattail.MOD_ID).map(c -> c.getModInfo().getVersion().toString())
                            .orElse("?"),
                    FMLPaths.GAMEDIR.get());
            session = s;
        }
        return s;
    }

    /** Render thread: the per-login values, then the record. */
    private static SysInfo build(Session s, SysInfo.Hardware hw, int renderDistance, long maxHeap) {
        return SysInfo.collect(hw, s.renderer(), System.getProperties(), maxHeap, renderDistance,
                SysInfo.irisShaderpack(s.gameDir(), s.irisLoaded()), s.modCount(), s.version());
    }

    /**
     * Main thread, configuration phase: the server asked. Answers at once when the hardware
     * summary is done; otherwise leaves it to {@link #onLoggingIn}, so no packet is ever sent for
     * this phase from another thread (it could land after the switch to play).
     */
    static void onRequest(IPayloadContext context) {
        if (!CattailConfig.SHARE_SPECS.get()) {
            return;
        }
        SysInfo.Hardware hw = hardware().getNow(null);
        if (hw == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        SysInfo info = build(session(), hw, mc.options.renderDistance().get(), Runtime.getRuntime().maxMemory());
        answered = new WeakReference<>(context.connection());
        context.reply(new SysInfoPayload(info));
        LOGGER.info("Cattail sent PC specs to the server (configuration phase)");
    }

    /** Render thread. Decides, reads the render-thread values and hands the rest to the IO pool. */
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!CattailConfig.SHARE_SPECS.get()) {
            return;
        }
        ClientPacketListener listener = event.getPlayer().connection;
        if (!listener.hasChannel(SysInfoPayload.TYPE)) {
            return; // vanilla or older server: never send
        }
        if (answered.get() == listener.getConnection()) {
            return; // sent during configuration
        }
        Session s = session();
        int renderDistance = Minecraft.getInstance().options.renderDistance().get();
        long maxHeap = Runtime.getRuntime().maxMemory();
        SpecsDispatch.sendWhenReady(hardware(), Util.ioPool(), hw -> build(s, hw, renderDistance, maxHeap),
                info -> {
                    // The channel set is fixed after configuration (checked above); only the connection can
                    // have gone. Connection.send is thread-safe: it hands the packet to the Netty event loop.
                    if (listener.getConnection().isConnected()) {
                        listener.send(new SysInfoPayload(info));
                        LOGGER.info("Cattail sent PC specs to the server");
                    }
                });
    }
}
