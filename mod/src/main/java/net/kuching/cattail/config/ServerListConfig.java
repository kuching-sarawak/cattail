package net.kuching.cattail.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.kuching.cattail.serverlist.ServerListInfo;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

/**
 * Dedicated-server config ({@code cattail-server.toml}): what players' server lists show about this
 * server ({@link ServerListInfo}). Registered only on a dedicated server, so a client never creates
 * the file. NeoForge reloads it when the file changes and the server rebuilds its status every 5 s,
 * so an edit reaches the next ping with no restart.
 */
public final class ServerListConfig {

    /** The spec, registered as {@code cattail-server.toml}. */
    public static final ModConfigSpec SPEC;
    /** {@code serverList.showIcon}: off, the status carries no {@code cattail} object at all. */
    public static final ModConfigSpec.BooleanValue SHOW_ICON;
    /** {@code serverList.lines}: the owner's tooltip lines as written, before {@link ServerListInfo#sanitised}. */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> LINES;

    /** The lines {@link #current} last saw, so a line left out or cut is warned about once per edit, not per rebuild. */
    private static List<String> lastLines;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("serverList");
        SHOW_ICON = b.comment("Show the Cattail icon with these lines on players' server lists.")
                .define("showIcon", true);
        // Mutable, not List.of: correcting a config puts this default instance itself into it.
        LINES = b.comment("One tooltip line each, in order. & plus a Minecraft formatting code styles the rest of",
                        "the line (&a green, &7 grey, &l bold, &r reset). At most " + ServerListInfo.MAX_LINES
                                + " lines of " + ServerListInfo.MAX_LINE + " characters.")
                .defineListAllowEmpty("lines", new ArrayList<>(List.of("Voice chat on", "Restarts daily 2:00 AM ET")),
                        () -> "", o -> o instanceof String);
        b.pop();
        SPEC = b.build();
    }

    private ServerListConfig() {
    }

    /**
     * The info this server's status carries, from the config as last loaded: empty when
     * {@code showIcon} is off, else {@code version} with the {@link ServerListInfo#sanitised sanitised}
     * lines. Each line sanitising leaves out or cuts is logged at WARN on {@code log}, once per
     * distinct {@code lines} list (the server asks every 5 s).
     */
    public static Optional<ServerListInfo> current(String version, Logger log) {
        return current(version, SHOW_ICON.getAsBoolean(), LINES.get(), log);
    }

    /** {@link #current(String, Logger)} on given values; the spec cannot be loaded in a unit test. */
    static Optional<ServerListInfo> current(String version, boolean showIcon, List<? extends String> lines, Logger log) {
        if (!showIcon) {
            return Optional.empty();
        }
        List<String> dropped = new ArrayList<>();
        ServerListInfo info = ServerListInfo.sanitised(version, Collections.unmodifiableList(lines), dropped::add);
        warnOncePerList(lines, dropped, log);
        return Optional.of(info);
    }

    private static synchronized void warnOncePerList(List<? extends String> raw, List<String> dropped, Logger log) {
        if (raw.equals(lastLines)) {
            return;
        }
        lastLines = new ArrayList<>(raw);
        for (String message : dropped) {
            log.warn("Cattail: cattail-server.toml serverList.lines: {}", message);
        }
    }
}
