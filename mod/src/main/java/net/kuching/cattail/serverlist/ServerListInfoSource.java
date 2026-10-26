package net.kuching.cattail.serverlist;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where {@code MinecraftServerMixin} gets the {@link ServerListInfo} for each status the server
 * builds (every 5 s). A dedicated server installs its config at start-up; with nothing installed (a
 * client's integrated server) there is no info and the status JSON has no {@code cattail} object.
 */
public final class ServerListInfoSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerListInfoSource.class);

    private static volatile Supplier<Optional<ServerListInfo>> source;
    private static volatile boolean failureLogged;

    private ServerListInfoSource() {
    }

    /** Makes {@code source} the one {@link #current} asks; called once, by the dedicated server's start-up. */
    public static void install(Supplier<Optional<ServerListInfo>> source) {
        ServerListInfoSource.source = Objects.requireNonNull(source, "source");
    }

    /**
     * The installed source's info, or empty when none is installed. Never throws: it runs in the
     * server tick, so a failing source gives no info (logged once at WARN) instead of a crash.
     */
    public static Optional<ServerListInfo> current() {
        Supplier<Optional<ServerListInfo>> s = source;
        if (s == null) {
            return Optional.empty();
        }
        try {
            Optional<ServerListInfo> info = s.get();
            return info == null ? Optional.empty() : info;
        } catch (RuntimeException e) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("Cattail: no server-list info for the status; players see no Cattail lines until this is fixed", e);
            } else {
                LOGGER.debug("Cattail: no server-list info for the status", e);
            }
            return Optional.empty();
        }
    }

    /** Tests only: back to nothing installed. */
    static void uninstall() {
        source = null;
        failureLogged = false;
    }
}
