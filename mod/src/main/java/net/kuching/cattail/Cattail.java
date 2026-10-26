package net.kuching.cattail;

import java.time.LocalDate;
import net.kuching.cattail.config.CattailConfig;
import net.kuching.cattail.deploy.Deployment;
import net.kuching.cattail.deploy.DeploymentStartupLog;
import net.kuching.cattail.specs.SpecsClient;
import net.kuching.cattail.specs.SpecsNetworking;
import net.kuching.cattail.update.UpdateNetworking;
import net.kuching.cattail.voice.VoiceServerStartupCheck;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point on both sides (one jar for players and the server). The server side registers the
 * {@code cattail:sysinfo} channel and logs the specs line, and sends its version on
 * {@code cattail:version} so an older client can show an update notice; a dedicated server also runs the
 * one-shot Plasmo Voice config check and warns in the log when the jar is not set up or its key
 * expires soon; everything else (config, helper, mixins) is client-only.
 */
@Mod(Cattail.MOD_ID)
public final class Cattail {

    public static final String MOD_ID = "cattail";
    private static final Logger LOGGER = LoggerFactory.getLogger(Cattail.class);

    public Cattail(IEventBus modBus, ModContainer container) {
        modBus.addListener(SpecsNetworking::register);
        modBus.addListener(UpdateNetworking::register);
        NeoForge.EVENT_BUS.addListener(UpdateNetworking::onPlayerLoggedIn);
        if (FMLEnvironment.dist.isClient()) {
            container.registerConfig(ModConfig.Type.CLIENT, CattailConfig.SPEC);
            modBus.addListener(CattailConfig::onLoading);
            modBus.addListener(CattailConfig::onReloading);
            NeoForge.EVENT_BUS.addListener(SpecsClient::onLoggingIn);
        }
        if (FMLEnvironment.dist.isDedicatedServer()) {
            VoiceServerStartupCheck.register();
            DeploymentStartupLog.log(Deployment.bundled(), LocalDate.now(), LOGGER);
        }
        LOGGER.info("Cattail {} loaded", container.getModInfo().getVersion());
    }
}
