package net.kuching.cattail.serverlist;

import java.util.Optional;

/**
 * An object that carries a server's {@link ServerListInfo}: the status record
 * {@code net.minecraft.network.protocol.status.ServerStatus}, which {@code ServerStatusMixin} makes
 * implement this interface with a mixin-added field (the pattern No Chat Reports uses on the same
 * record). Code reaches it through a cast to {@code Object} first, since the compiler does not know
 * a final class implements an interface a mixin adds; {@link ServerListInfo#of} does that for a status.
 */
public interface CattailStatusCarrier {

    /** The carried info; empty when the status has no {@code cattail} object or none was set. */
    Optional<ServerListInfo> cattail$info();

    /** Replaces the carried info; empty clears it. */
    void cattail$setInfo(Optional<ServerListInfo> info);
}
