package net.kuching.cattail.voice;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.kuching.cattail.voice.VoiceServerConfigCheck.Finding;
import net.kuching.cattail.voice.VoiceServerConfigCheck.Severity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated-server-only, one-shot check of the Plasmo Voice server config. Plasmo loads its config
 * and starts its UDP server from its own server-started listener, so the check runs on the first
 * server tick after start, logs every {@link Finding} at its severity, and unregisters itself.
 * When no finding is a WARN (no findings, or INFO hints only) it also logs one INFO summary line
 * saying the config was checked with no problems. When Plasmo Voice is not loaded (the no-voice
 * jar, or no Plasmo Voice in mods) it logs {@link VoiceServerConfigCheck#NOT_LOADED} alone. It
 * never references Plasmo classes and never throws out of the tick handler.
 */
public final class VoiceServerStartupCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoiceServerStartupCheck.class);

    private final AtomicBoolean done = new AtomicBoolean();

    private VoiceServerStartupCheck() {
    }

    /** Registers the one-shot check on the game event bus. Call only on a dedicated server. */
    public static void register() {
        NeoForge.EVENT_BUS.register(new VoiceServerStartupCheck());
    }

    /** Runs the check on the first server tick, then unregisters. */
    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        try {
            // Plasmo opens its config with new File("config/plasmovoice/server/config.toml"), which
            // resolves against the JVM working directory, so resolve the same way rather than
            // against the game directory, which a launcher can point elsewhere.
            Path configFile = Path.of("").toAbsolutePath().resolve(VoiceServerConfigCheck.CONFIG_RELATIVE_PATH);
            boolean plasmoLoaded = ModList.get().isLoaded(VoiceServerConfigCheck.PLASMO_MOD_ID);
            List<Finding> findings = VoiceServerConfigCheck.inspect(plasmoLoaded, configFile,
                    event.getServer().getPort());
            boolean anyWarn = false;
            for (Finding finding : findings) {
                if (finding.severity() == Severity.WARN) {
                    anyWarn = true;
                    LOGGER.warn(finding.message());
                } else {
                    LOGGER.info(finding.message());
                }
            }
            if (plasmoLoaded && !anyWarn) {
                LOGGER.info("Cattail voice: Plasmo Voice config checked, no problems");
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Cattail voice: Plasmo Voice config check failed", e);
        } finally {
            NeoForge.EVENT_BUS.unregister(this);
        }
    }
}
