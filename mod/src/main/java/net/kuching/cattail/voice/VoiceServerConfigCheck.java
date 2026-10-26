package net.kuching.cattail.voice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlFormat;

/**
 * Inspects the bundled Plasmo Voice server config for settings that break voice for tailnet
 * players. Tailnet clients reach the server only through the game port (and whatever the TXT
 * record and tailnet policy publish), so a voice server bound to one address by {@code [host] ip},
 * a moved {@code [host] port} or a fixed {@code [host.public] ip} silently cuts them off. Pure
 * Java: no NeoForge, Minecraft or Plasmo types, so it runs in unit tests without the game.
 *
 * <p>Plasmo Voice 2.1.17 binds its UDP server to {@code [host] ip} (default {@code "0.0.0.0"}) and
 * sends clients the address {@code [host] ip}, replaced by {@code [host.public] ip} (default
 * {@code "127.0.0.1"} when the key is missing) if {@code [host.public]} exists. Clients use that
 * address as-is unless it is {@code "0.0.0.0"}, in which case they keep the address they joined
 * with.
 */
public final class VoiceServerConfigCheck {

    /**
     * Plasmo Voice server config location, relative to the JVM working directory (Plasmo opens it
     * with {@code new File(...)} on this relative path, not against the game directory).
     */
    public static final String CONFIG_RELATIVE_PATH = "config/plasmovoice/server/config.toml";

    /** Plasmo Voice's mod id, as NeoForge's mod list names it. */
    public static final String PLASMO_MOD_ID = "plasmovoice";

    /**
     * The one finding when Plasmo Voice is not loaded at all: the server runs the no-voice jar, or
     * a standalone Plasmo Voice was never installed. Not a problem, so INFO.
     */
    public static final Finding NOT_LOADED = new Finding(Severity.INFO,
            "Cattail voice: Plasmo Voice is not loaded (the no-voice jar, or no Plasmo Voice in mods); "
                    + "voice is off for tailnet players");

    private static final String WILDCARD_IP = "0.0.0.0";

    /** What Plasmo Voice uses for {@code [host.public] ip} when the key is missing. */
    private static final String PUBLIC_IP_DEFAULT = "127.0.0.1";

    /** How loudly a finding is logged. */
    public enum Severity {
        INFO,
        WARN
    }

    /** One problem (or hint) found in the config, with the exact line to log. */
    public record Finding(Severity severity, String message) {
    }

    private VoiceServerConfigCheck() {
    }

    /**
     * {@link #inspect(Path, int)} when Plasmo Voice is loaded; {@link #NOT_LOADED} alone when it is
     * not, since its config then means nothing (and is normally absent).
     */
    public static List<Finding> inspect(boolean plasmoLoaded, Path configFile, int gamePort) {
        if (!plasmoLoaded) {
            return List.of(NOT_LOADED);
        }
        return inspect(configFile, gamePort);
    }

    /**
     * Reads {@code configFile} as UTF-8 and inspects it. A missing file yields one WARN (Plasmo
     * writes the file when its server starts, so absence means the voice server is not running).
     * Never throws: a read failure yields one WARN.
     */
    public static List<Finding> inspect(Path configFile, int gamePort) {
        if (!Files.exists(configFile)) {
            return List.of(new Finding(Severity.WARN,
                    "Cattail voice: Plasmo Voice config not found at " + configFile
                            + "; the voice server is not running."));
        }
        String toml;
        try {
            toml = Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of(new Finding(Severity.WARN, "Cattail voice: could not read config.toml: " + e.getMessage()));
        }
        return inspect(toml, gamePort);
    }

    /**
     * Inspects TOML text against {@code gamePort}. An empty list means the config is clean. Never
     * throws: unparseable text yields one WARN.
     */
    public static List<Finding> inspect(String toml, int gamePort) {
        Config config;
        try {
            config = TomlFormat.instance().createParser().parse(toml);
        } catch (RuntimeException e) {
            return List.of(new Finding(Severity.WARN, "Cattail voice: could not parse config.toml: " + e.getMessage()));
        }

        List<Finding> findings = new ArrayList<>();

        Object hostIp = config.get(List.of("host", "ip"));
        if (hostIp != null && !WILDCARD_IP.equals(hostIp)) {
            findings.add(new Finding(Severity.WARN, "Cattail voice: Plasmo Voice [host] ip = \"" + hostIp
                    + "\" binds the voice server to one address; tailnet players and LAN players reach the server on"
                    + " different addresses. Set ip = \"0.0.0.0\" in config/plasmovoice/server/config.toml."));
        }

        long hostPort = port(config.get(List.of("host", "port")));
        if (hostPort != 0 && hostPort != gamePort) {
            findings.add(new Finding(Severity.WARN, "Cattail voice: Plasmo Voice [host] port is " + hostPort
                    + " but the game port is " + gamePort + "; tailnet players send voice to the game port. Set port = 0"
                    + " in config/plasmovoice/server/config.toml, or publish voice=" + hostPort
                    + " in the TXT record and grant udp:" + hostPort + "."));
        }

        Object publicSection = config.get(List.of("host", "public"));
        if (publicSection instanceof Config hostPublic) {
            Object ip = hostPublic.<Object>getOrElse("ip", PUBLIC_IP_DEFAULT);
            long publicPort = port(hostPublic.get("port"));
            if (!WILDCARD_IP.equals(ip)) {
                findings.add(new Finding(Severity.WARN, "Cattail voice: Plasmo Voice [host.public] ip = \"" + ip
                        + "\" breaks tailnet and LAN players. Set ip = \"0.0.0.0\" (clients then keep the address they"
                        + " joined with) or remove [host.public]."));
            } else if (publicPort != 0 && publicPort != gamePort) {
                findings.add(new Finding(Severity.INFO, "Cattail voice: Plasmo Voice [host.public] port " + publicPort
                        + " differs from the game port " + gamePort + "; make sure the TXT record has voice="
                        + publicPort + " and the policy grants udp:" + publicPort + "."));
            }
        }

        return List.copyOf(findings);
    }

    /**
     * night-config returns a TOML integer as {@code Integer} when it fits in an int and as
     * {@code Long} above that, hence {@code Number}; anything absent or non-numeric counts as 0.
     */
    private static long port(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0;
    }
}
