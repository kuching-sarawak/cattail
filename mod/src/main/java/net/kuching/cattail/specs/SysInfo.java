package net.kuching.cattail.specs;

import java.io.IOException;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import net.kuching.cattail.deploy.Deployment;
import net.kuching.cattail.deploy.DeploymentSettings;
import net.kuching.cattail.text.SafeText;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;

/**
 * The player's PC specs, sent once per login to a server that advertises {@code cattail:sysinfo}.
 * Holds no IP address, host name or serial number. Strings are cut to {@link #MAX_FIELD} chars by
 * {@link #collect} on the client and sanitised again by {@link #toLogLine} on the server (the
 * payload comes from the network, so the server never trusts it).
 *
 * @param shaderpack Iris shaderpack file name, empty = none
 */
public record SysInfo(String cpu, int cores, int threads, long ramBytes, String gpu, long vramBytes, String driver,
        String os, String java, long heapBytes, int renderDistance, String shaderpack, int mods,
        String cattailVersion) {

    /** Longest field value in the log line, in chars. */
    public static final int MAX_FIELD = 64;
    /** Longest log line, in UTF-8 bytes. */
    public static final int MAX_LINE_BYTES = 1024;

    private static final double GIB = 1L << 30;

    /** One graphics card as OSHI reports it; {@code versionInfo} is e.g. {@code DriverVersion=32.0.15.6094}. */
    public record Gpu(String name, long vramBytes, String versionInfo) {}

    /** OSHI's hardware summary (slow to read on Windows, so read once and kept). */
    public record Hardware(String cpu, int cores, int threads, long ramBytes, List<Gpu> gpus) {
        public Hardware {
            gpus = List.copyOf(gpus);
        }
    }

    /**
     * Builds the record from its sources. The GPU is the OSHI card the game renders on (its name
     * matches the GL renderer string), else the first card, else the GL renderer itself.
     *
     * @param sys {@code os.name}, {@code os.version}, {@code os.arch}, {@code java.version}
     */
    public static SysInfo collect(Hardware hw, String glRenderer, Properties sys, long maxHeap, int renderDistance,
            Optional<String> shaderpack, int mods, String cattailVersion) {
        String renderer = glRenderer == null ? "" : glRenderer;
        int slash = renderer.indexOf('/');
        String rendererName = (slash >= 0 ? renderer.substring(0, slash) : renderer).trim();
        Optional<Gpu> card = pickGpu(hw.gpus(), rendererName);
        String gpu = card.map(Gpu::name).orElse(rendererName.isEmpty() ? "unknown" : rendererName);
        String driver = card.map(c -> stripPrefix(c.versionInfo(), "DriverVersion=")).filter(s -> !s.isBlank())
                .orElse("unknown");
        String os = String.join(" ", sys.getProperty("os.name", "?"), sys.getProperty("os.version", "?"),
                sys.getProperty("os.arch", "?"));
        return new SysInfo(clip(hw.cpu()), hw.cores(), hw.threads(), hw.ramBytes(), clip(gpu),
                card.map(Gpu::vramBytes).orElse(0L), clip(driver), clip(os), clip(sys.getProperty("java.version", "?")),
                maxHeap, renderDistance, clip(shaderpack.orElse("")), mods, clip(cattailVersion));
    }

    /** {@link #linePrefix(Deployment)} of the jar's own {@link Deployment#bundled() deployment}. */
    public static String linePrefix() {
        return linePrefix(Deployment.bundled());
    }

    /**
     * The log line prefix including its trailing {@code |}: the deployment's {@code specs.prefix}
     * (already validated, and {@value DeploymentSettings#DEFAULT_PREFIX} when the file has none), or
     * {@value DeploymentSettings#DEFAULT_PREFIX} for a generic jar or a rejected deployment file.
     */
    static String linePrefix(Deployment d) {
        String prefix = switch (d) {
            case Deployment.Generic() -> DeploymentSettings.DEFAULT_PREFIX;
            case Deployment.Invalid(String key) -> DeploymentSettings.DEFAULT_PREFIX;
            case Deployment.Configured(DeploymentSettings s) -> s.specsPrefix();
        };
        return prefix + "|";
    }

    /** {@link #toLogLine(String, SysInfo, String, String)} with the bundled {@link #linePrefix()}. */
    public static String toLogLine(String player, SysInfo i, String ip) {
        String prefix = linePrefix();
        return toLogLine(player, i, prefix.substring(0, prefix.length() - 1), ip);
    }

    /**
     * The server console line:
     * {@code <prefix>|<player>|<cpu>|<c>c/<t>t|<ram> GiB|<gpu>|<vram> GiB|<driver>|<os>|Java <ver>|heap <h> GiB|render <n>|<shaderpack or none>|<mods> mods|cattail <ver>|ip <addr>}.
     * Each field has {@code |} replaced by {@code /} and control and format characters by spaces and is at
     * most {@value #MAX_FIELD} chars; the whole line is at most {@value #MAX_LINE_BYTES} UTF-8 bytes.
     *
     * @param prefix the line prefix without its {@code |}, e.g. {@value DeploymentSettings#DEFAULT_PREFIX}
     * @param ip     the client's address as {@link #hostOf} gives it (a tailnet IP for a Cattail player)
     */
    public static String toLogLine(String player, SysInfo i, String prefix, String ip) {
        String shader = field(i.shaderpack());
        List<String> fields = List.of(
                field(player),
                field(i.cpu()),
                i.cores() + "c/" + i.threads() + "t",
                gib(i.ramBytes()) + " GiB",
                field(i.gpu()),
                gib(i.vramBytes()) + " GiB",
                field(i.driver()),
                field(i.os()),
                field("Java " + field(i.java())),
                "heap " + gib(i.heapBytes()) + " GiB",
                "render " + i.renderDistance(),
                shader.isEmpty() ? "none" : shader,
                i.mods() + " mods",
                field("cattail " + field(i.cattailVersion())),
                field("ip " + field(ip)));
        return capBytes(prefix + "|" + String.join("|", fields), MAX_LINE_BYTES);
    }

    /**
     * The bare IP of a connection's remote address: digits, hex digits, dots and colons only (no
     * port, no brackets, no IPv6 zone), so a log parser can anchor on it; {@code unknown} for
     * anything else (null, an unresolved address, a local pipe).
     */
    public static String hostOf(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            String ip = inet.getAddress().getHostAddress();
            int zone = ip.indexOf('%');
            return zone < 0 ? ip : ip.substring(0, zone);
        }
        return "unknown";
    }

    /**
     * The Iris shaderpack in use, read from {@code config/iris.properties} (key {@code shaderPack};
     * empty when Iris is not loaded, the file is missing, or {@code enableShaders=false}).
     */
    public static Optional<String> irisShaderpack(Path gameDir, boolean irisLoaded) {
        if (!irisLoaded) {
            return Optional.empty();
        }
        Path file = gameDir.resolve("config").resolve("iris.properties");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
        if ("false".equalsIgnoreCase(p.getProperty("enableShaders", "true").trim())) {
            return Optional.empty();
        }
        return Optional.ofNullable(p.getProperty("shaderPack")).map(String::trim).filter(s -> !s.isEmpty());
    }

    /** Reads the hardware summary through Minecraft's bundled OSHI. Slow (WMI on Windows): call off the render thread. */
    public static Hardware readHardware() {
        HardwareAbstractionLayer hal = new SystemInfo().getHardware();
        CentralProcessor cpu = hal.getProcessor();
        List<Gpu> gpus = new ArrayList<>();
        for (GraphicsCard c : hal.getGraphicsCards()) {
            gpus.add(new Gpu(c.getName(), c.getVRam(), c.getVersionInfo()));
        }
        return new Hardware(cpu.getProcessorIdentifier().getName().trim(), cpu.getPhysicalProcessorCount(),
                cpu.getLogicalProcessorCount(), hal.getMemory().getTotal(), gpus);
    }

    private static Optional<Gpu> pickGpu(List<Gpu> gpus, String rendererName) {
        String r = rendererName.toLowerCase(Locale.ROOT);
        if (!r.isEmpty()) {
            for (Gpu g : gpus) {
                String n = g.name() == null ? "" : g.name().trim().toLowerCase(Locale.ROOT);
                if (!n.isEmpty() && (r.contains(n) || n.contains(r))) {
                    return Optional.of(g);
                }
            }
        }
        return gpus.stream().findFirst();
    }

    private static String stripPrefix(String s, String prefix) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.startsWith(prefix) ? t.substring(prefix.length()).trim() : t;
    }

    /**
     * {@code |} to {@code /}, control and format characters to spaces ({@link SafeText#withoutControls}),
     * trimmed, cut to {@link #MAX_FIELD} chars.
     */
    static String field(String s) {
        return clip(SafeText.withoutControls(s).replace('|', '/').trim());
    }

    /** Cut to {@link #MAX_FIELD} chars, never inside a surrogate pair; null to empty. */
    private static String clip(String s) {
        return SafeText.clip(s, MAX_FIELD);
    }

    /** Whole GiB from 10 up, else one decimal without a trailing {@code .0}. */
    static String gib(long bytes) {
        double v = bytes / GIB;
        if (v >= 10) {
            return Long.toString(Math.round(v));
        }
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** Cuts to at most {@code maxBytes} of UTF-8 in one pass over the code points, never inside a surrogate pair. */
    private static String capBytes(String s, int maxBytes) {
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int n = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + n > maxBytes) {
                return s.substring(0, i);
            }
            bytes += n;
            i += Character.charCount(cp);
        }
        return s;
    }
}
