package net.kuching.cattail.config;

import net.kuching.cattail.Redirector;
import net.kuching.cattail.helper.HelperManager;
import net.kuching.cattail.specs.SpecsClient;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client config ({@code cattail-client.toml}). */
public final class CattailConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(CattailConfig.class);
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]{0,252})");

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FORCED_HOSTS;
    public static final ModConfigSpec.BooleanValue FORCE_RELAY;
    public static final ModConfigSpec.ConfigValue<String> HELPER_LOG_LEVEL;
    public static final ModConfigSpec.BooleanValue SHARE_SPECS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Route servers that publish a cattail=1 TXT record through the Tailscale helper.")
                .define("enabled", true);
        FORCED_HOSTS = b.comment("Hosts always routed to a tailnet IPv4, as host=ip (e.g. mc.example.com=100.101.102.103).")
                .defineListAllowEmpty("forcedHosts", List.of(), () -> "", CattailConfig::isValidForcedHost);
        FORCE_RELAY = b.comment("Force the helper to use Tailscale's DERP relays instead of a direct connection.")
                .define("forceRelay", false);
        // Not List.of: NeoForge validates an EMPTY config on first launch, calling contains(null),
        // which an immutable list rejects with NullPointerException and mod loading fails.
        HELPER_LOG_LEVEL = b.comment("Helper log level: debug, info or warn.")
                .defineInList("helperLogLevel", "info", new ArrayList<>(List.of("debug", "info", "warn")));
        SHARE_SPECS = b.comment("Send your PC specs (CPU, RAM, GPU, driver, OS, Java, heap, render distance, shaderpack,",
                        "mod count, Cattail version; no IP, host name or serial numbers) once per login to servers",
                        "that ask for them (cattail:sysinfo). The server logs them once per login.")
                .define("shareSpecs", true);
        SPEC = b.build();
    }

    private CattailConfig() {
    }

    /** The configured helper log level ({@code debug}, {@code info} or {@code warn}). */
    public static String helperLogLevel() {
        return HELPER_LOG_LEVEL.get();
    }

    public static void onLoading(ModConfigEvent.Loading event) {
        apply(event);
    }

    public static void onReloading(ModConfigEvent.Reloading event) {
        apply(event);
    }

    private static void apply(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        HelperManager.forceRelaySetting = FORCE_RELAY::get;
        Map<String, InetAddress> forced = parseForcedHosts(FORCED_HOSTS.get());
        Redirector.get().reconfigure(ENABLED.get(), forced);
        if (SHARE_SPECS.get()) {
            SpecsClient.prewarm(); // read the hardware now, so the first login sends at once
        }
        LOGGER.info("Cattail config applied: enabled={}, forcedHosts={}, forceRelay={}, shareSpecs={}",
                ENABLED.get(), forced.keySet(), FORCE_RELAY.get(), SHARE_SPECS.get());
    }

    /** True for a string {@code host=a.b.c.d}. */
    static boolean isValidForcedHost(Object o) {
        return o instanceof String s && parseEntry(s) != null;
    }

    /** Valid entries as host (lower-cased) to IPv4; invalid ones are skipped. */
    static Map<String, InetAddress> parseForcedHosts(List<? extends String> entries) {
        Map<String, InetAddress> out = new LinkedHashMap<>();
        for (String e : entries) {
            Map.Entry<String, InetAddress> parsed = parseEntry(e);
            if (parsed != null) {
                out.put(parsed.getKey(), parsed.getValue());
            }
        }
        return out;
    }

    private static Map.Entry<String, InetAddress> parseEntry(String entry) {
        if (entry == null) {
            return null;
        }
        int eq = entry.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String host = entry.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        String ip = entry.substring(eq + 1).trim();
        if (!HOST.matcher(host).matches()) {
            return null;
        }
        var m = IPV4.matcher(ip);
        if (!m.matches()) {
            return null;
        }
        for (int i = 1; i <= 4; i++) {
            if (Integer.parseInt(m.group(i)) > 255) {
                return null;
            }
        }
        try {
            return Map.entry(host, InetAddress.getByName(ip)); // dotted-quad literal: no DNS query
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
