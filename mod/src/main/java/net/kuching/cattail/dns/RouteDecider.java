package net.kuching.cattail.dns;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a server address is "ours" (spec §6.2): its resolved IPv4 (or the TXT
 * {@code target=} when resolution yields nothing) lies in 100.64.0.0/10 AND its TXT record
 * carries {@code cattail=1} (or the legacy key {@code tslink=1}, accepted so records published
 * before the rename keep working). IP literals and {@code localhost} are never routed.
 *
 * <p>Voice relay: a TXT record without a usable {@code voice=} field relays voice on the game
 * port (the bundled voice server listens there), {@code voice=0} turns the relay off and
 * {@code voice=<n>} relays port {@code n}. Forced hosts relay voice on the game port.
 */
public final class RouteDecider {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteDecider.class);
    private static final Pattern IPV4_LITERAL =
            Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    /** {@link Decision#voicePort} value meaning "use the game port of the call". */
    private static final int VOICE_DEFAULT = -1;
    /** The TXT key that marks a server as ours. */
    static final String TXT_KEY = "cattail";
    /** The pre-rename key, still accepted so existing records keep routing. */
    static final String LEGACY_TXT_KEY = "tslink";

    /**
     * Cached per-host outcome; the port is supplied per call, so {@code voicePort} may be
     * {@link #VOICE_DEFAULT} and is resolved against it when the {@link Route} is built.
     */
    private record Decision(InetAddress target, int voicePort) {}

    private final TxtRecords txt;
    private final Function<String, Optional<InetAddress>> resolver;
    private final Map<String, InetAddress> forcedHosts;
    private final Map<String, Optional<Decision>> cache = new ConcurrentHashMap<>();

    public RouteDecider(TxtRecords txt, Function<String, Optional<InetAddress>> resolver,
            Map<String, InetAddress> forcedHosts) {
        this.txt = txt;
        this.resolver = resolver;
        Map<String, InetAddress> forced = new HashMap<>();
        forcedHosts.forEach((k, v) -> forced.put(k.toLowerCase(Locale.ROOT), v));
        this.forcedHosts = Map.copyOf(forced);
    }

    public Optional<Route> decide(String host, int port) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String key = host.trim().toLowerCase(Locale.ROOT);

        InetAddress forced = forcedHosts.get(key);
        if (forced != null) {
            LOGGER.debug("{}: forced host -> {}", key, forced.getHostAddress());
            return Optional.of(new Route(key, forced, port, port));
        }
        if (isLiteralOrLocalhost(key)) {
            LOGGER.debug("{}: IP literal or localhost, not routed", key);
            return Optional.empty();
        }

        Optional<Decision> decision = cache.get(key);
        if (decision == null) {
            decision = lookup(key);
            if (decision == null) {
                return Optional.empty(); // lookup failed: not ours now, ask again next time
            }
            Optional<Decision> raced = cache.putIfAbsent(key, decision);
            if (raced != null) {
                decision = raced;
            }
        }
        return decision.map(d -> new Route(key, d.target(), port, voiceFor(d, port)));
    }

    /**
     * The route for {@code host} from the cache alone, never resolving or querying TXT (safe on the
     * render thread). Forced hosts always answer; a host {@link #decide} has not seen yet is empty.
     */
    public Optional<Route> cached(String host, int port) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String key = host.trim().toLowerCase(Locale.ROOT);
        InetAddress forced = forcedHosts.get(key);
        if (forced != null) {
            return Optional.of(new Route(key, forced, port, port));
        }
        Optional<Decision> decision = cache.get(key);
        if (decision == null) {
            return Optional.empty();
        }
        return decision.map(d -> new Route(key, d.target(), port, voiceFor(d, port)));
    }

    private static int voiceFor(Decision d, int port) {
        return d.voicePort() == VOICE_DEFAULT ? port : d.voicePort();
    }

    public void clearCache() {
        cache.clear();
    }

    /**
     * Returns the definite outcome for {@code host} (routed or not routed, both cacheable), or
     * {@code null} when the TXT lookup failed, which must not be cached. Order: resolver (A)
     * first; only an empty answer or one inside 100.64.0.0/10 goes on to the TXT lookup.
     */
    private Optional<Decision> lookup(String host) {
        // A record first: a public IPv4 can never be ours, so skip the TXT query entirely (a
        // resolver that drops TXT would otherwise cost seconds on every ping to any server).
        InetAddress resolved = resolveIpv4(host);
        if (resolved != null && !isCgnatIpv4(resolved)) {
            LOGGER.debug("{}: {} is not an IPv4 in 100.64.0.0/10, not routed (no TXT query)",
                    host, resolved.getHostAddress());
            return Optional.empty();
        }

        List<String> records;
        try {
            records = txt.lookup(host);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("{}: TXT lookup failed ({}), not routed, not cached", host, e.toString());
            return null;
        }
        if (records == null) {
            records = List.of();
        }

        Map<String, String> fields = null;
        for (String record : records) {
            if (record == null) {
                continue;
            }
            Map<String, String> parsed = parse(record);
            if (isOurs(parsed)) {
                fields = parsed;
                break;
            }
        }
        if (fields == null) {
            LOGGER.debug("{}: no cattail=1 (or legacy tslink=1) TXT, not routed", host);
            return Optional.empty();
        }

        InetAddress target;
        if (resolved != null) {
            target = resolved;
        } else {
            target = parseIpv4(fields.get("target"));
            if (target == null) {
                LOGGER.debug("{}: unresolved and no valid TXT target, not routed", host);
                return Optional.empty();
            }
        }
        if (!isCgnatIpv4(target)) {
            LOGGER.debug("{}: {} is not an IPv4 in 100.64.0.0/10, not routed",
                    host, target.getHostAddress());
            return Optional.empty();
        }

        String rawVoice = fields.get("voice");
        int voice = parseVoice(rawVoice);
        LOGGER.debug("{}: routed -> {} (voice {})", host, target.getHostAddress(), describeVoice(rawVoice, voice));
        return Optional.of(new Decision(target, voice));
    }

    /** The voice part of the routed debug line: the port, {@code default}, or the invalid raw value. */
    private static String describeVoice(String rawVoice, int voice) {
        if (voice != VOICE_DEFAULT) {
            return Integer.toString(voice);
        }
        if (rawVoice == null) {
            return "default";
        }
        return "\"" + rawVoice + "\" invalid, using the game port";
    }

    /**
     * The resolver's answer if it is an IPv4 address; {@code null} (= "resolver empty", so the
     * TXT {@code target=} fallback applies) when it is empty, null, non-IPv4, or throws.
     */
    private InetAddress resolveIpv4(String host) {
        Optional<InetAddress> resolved;
        try {
            resolved = resolver.apply(host);
        } catch (RuntimeException e) {
            LOGGER.debug("{}: resolver failed ({}), treating as empty", host, e.toString());
            return null;
        }
        if (resolved == null || resolved.isEmpty()) {
            return null;
        }
        InetAddress a = resolved.get();
        if (!(a instanceof Inet4Address)) {
            LOGGER.debug("{}: resolver gave non-IPv4 {}, treating as empty", host, a.getHostAddress());
            return null;
        }
        return a;
    }

    /** Splits {@code "a=1; b = 2"} on ';', each field on its first '='; keys lower-cased. */
    private static boolean isOurs(Map<String, String> fields) {
        return "1".equals(fields.get(TXT_KEY)) || "1".equals(fields.get(LEGACY_TXT_KEY));
    }

    private static Map<String, String> parse(String record) {
        Map<String, String> out = new HashMap<>();
        for (String field : record.split(";")) {
            int eq = field.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = field.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = field.substring(eq + 1).trim();
            if (!k.isEmpty()) {
                out.putIfAbsent(k, v);
            }
        }
        return out;
    }

    private static InetAddress parseIpv4(String s) {
        if (s == null || !IPV4_LITERAL.matcher(s).matches()) {
            return null;
        }
        for (String octet : s.split("\\.")) {
            if (Integer.parseInt(octet) > 255) {
                return null;
            }
        }
        try {
            return InetAddress.getByName(s); // dotted-quad literal: no DNS query
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * The {@code voice=} value: the port for {@code 1..65535}, {@code 0} (relay off) for
     * {@code 0}, {@link #VOICE_DEFAULT} when absent, not an integer, or out of range.
     */
    private static int parseVoice(String s) {
        if (s == null) {
            return VOICE_DEFAULT;
        }
        try {
            int v = Integer.parseInt(s);
            return v >= 0 && v <= 65535 ? v : VOICE_DEFAULT;
        } catch (NumberFormatException e) {
            return VOICE_DEFAULT;
        }
    }

    /** True iff {@code a} is IPv4 in 100.64.0.0/10 (100.64.0.0 - 100.127.255.255). */
    static boolean isCgnatIpv4(InetAddress a) {
        if (!(a instanceof Inet4Address)) {
            return false;
        }
        byte[] b = a.getAddress();
        return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }

    private static boolean isLiteralOrLocalhost(String host) {
        return host.equals("localhost")
                || host.contains(":") // IPv6 literal (with or without brackets)
                || IPV4_LITERAL.matcher(host).matches();
    }
}
