package net.kuching.cattail.dns;

import java.net.InetAddress;

/**
 * A connection that goes through the Cattail helper.
 *
 * @param host      the host name the player typed (lower-cased)
 * @param target    tailnet IPv4 of the server, inside 100.64.0.0/10
 * @param port      game port the player asked for
 * @param voicePort voice UDP port, the game port unless the TXT record says otherwise,
 *                  0 = no voice relay
 */
public record Route(String host, InetAddress target, int port, int voicePort) {}
