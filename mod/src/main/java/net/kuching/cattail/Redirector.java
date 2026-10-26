package net.kuching.cattail;

import net.kuching.cattail.dns.JndiTxtRecords;
import net.kuching.cattail.dns.Route;
import net.kuching.cattail.dns.RouteDecider;
import net.kuching.cattail.helper.HelperManager;
import net.kuching.cattail.helper.HelperStartException;
import net.kuching.cattail.helper.HelperStatus;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Glue between the game's connect path (the mixins) and {@link RouteDecider} / {@link HelperManager}.
 * Every method that may block (DNS, helper start) must run off the render thread.
 */
public final class Redirector {

    private static final Logger LOGGER = LoggerFactory.getLogger(Redirector.class);
    private static final Set<String> VISIBLE_UNROUTED = Set.of("helper-failed", "unsupported-platform");
    /**
     * Status key of a rejected deployment file, whose failure reason is
     * {@link HelperStartException#DEPLOYMENT_INVALID}{@code <key>}.
     */
    private static final String DEPLOYMENT_INVALID = "deployment-invalid";

    /** The parts of {@link HelperManager} the redirector uses; faked in unit tests. */
    interface HelperGateway {
        void ensureStartingAsync(Route r);

        Optional<InetSocketAddress> loopbackFor(Route r);

        /** Attaches a ping to the helper only when it is idle or already on {@code r}; false = busy. */
        boolean ensureStartingIfIdleOrSame(Route r);

        /** Waits for the helper to serve {@code r} without starting or switching it. */
        Optional<InetSocketAddress> awaitLoopback(Route r);

        HelperStatus status();

        /** True when the helper is READY and serving {@code r}'s endpoint; never blocks for long. */
        boolean readyFor(Route r);

        Optional<String> failureReason();
    }

    /**
     * Outcome of the last connect attempt, shown on the connect screen as
     * {@code cattail.status.<key>} with {@code arg} as its {@code %s}.
     */
    public record Status(String key, String arg, boolean routed) {
        /** Whether the connect screen should draw this status. */
        public boolean visible() {
            return routed || VISIBLE_UNROUTED.contains(key);
        }
    }

    /** A rewrite already done for {@code original} on this thread, reused by {@link #connectAddress}. */
    private record Prepared(InetSocketAddress original, InetSocketAddress result) {}

    private static volatile Redirector instance;

    private volatile RouteDecider decider;
    private final HelperGateway gateway;
    private final BooleanSupplier platformOk;
    private volatile BooleanSupplier enabled;
    /** Generation passed by callers whose outcome must not touch the status line. */
    private static final long NO_STATUS = -1;

    private volatile Status lastStatus;
    /** Connect-attempt generation; bumped by {@link #clearStatus}, so a late write of an old attempt is dropped. */
    private final AtomicLong generation = new AtomicLong();
    private final ThreadLocal<Prepared> prepared = new ThreadLocal<>();
    private final AtomicBoolean earlyStartRunning = new AtomicBoolean();

    Redirector(RouteDecider decider, HelperGateway gateway, BooleanSupplier platformOk, BooleanSupplier enabled) {
        this.decider = decider;
        this.gateway = gateway;
        this.platformOk = platformOk;
        this.enabled = enabled;
    }

    /** The session-wide redirector, wired to DNS and the real {@link HelperManager}. */
    public static Redirector get() {
        Redirector r = instance;
        if (r == null) {
            synchronized (Redirector.class) {
                r = instance;
                if (r == null) {
                    r = new Redirector(productionDecider(Map.of()), new ManagerGateway(),
                            Platform::helperSupported, () -> true);
                    instance = r;
                }
            }
        }
        return r;
    }

    /** Applies the config: on/off switch and forced hosts (a fresh decider, so the cache starts empty). */
    public void reconfigure(boolean enabled, Map<String, InetAddress> forcedHosts) {
        reconfigure(enabled, productionDecider(forcedHosts));
    }

    void reconfigure(boolean enabled, RouteDecider newDecider) {
        RouteDecider old = this.decider;
        this.decider = newDecider;
        this.enabled = () -> enabled;
        old.clearCache();
    }

    /**
     * The address to open the game connection to: the helper's loopback port when
     * {@code original}'s host is ours and the helper is ready, else {@code original}. Records the
     * outcome for the status line. Blocks on DNS and on the helper start (up to ~30 s).
     */
    public InetSocketAddress rewrite(InetSocketAddress original) {
        return route(original, generation.get());
    }

    /** Like {@link #rewrite} but leaves the status line alone (server-list pings). */
    public InetSocketAddress rewriteQuiet(InetSocketAddress original) {
        return route(original, NO_STATUS);
    }

    /**
     * Does the (blocking) {@link #rewrite} for {@code original} now and keeps the result for this
     * thread, so that {@link #connectAddress} can answer without blocking. The connector thread calls
     * this right after resolving, before it takes the lock the Cancel button also takes.
     */
    public void prepare(InetSocketAddress original) {
        long gen = generation.get(); // this attempt; a Cancel + new attempt bumps it
        prepared.set(new Prepared(original, route(original, gen)));
    }

    /**
     * The prepared rewrite of {@code original} on this thread, else {@code original} unchanged. Never
     * blocks: the caller holds the lock the Cancel button (render thread) also takes.
     */
    public InetSocketAddress connectAddress(InetSocketAddress original) {
        Prepared p = prepared.get();
        prepared.remove();
        if (p != null && p.original().equals(original)) {
            return p.result();
        }
        LOGGER.debug("No prepared route for {} (prepared: {}), connecting unchanged",
                original, p == null ? "none" : p.original());
        return original;
    }

    /**
     * Fallback when the normal resolver finds nothing: the route target of a host that is ours
     * (e.g. via TXT {@code target=}). Blocks on DNS.
     */
    public Optional<ResolvedServerAddress> resolveFallback(ServerAddress address) {
        return fallbackAddress(address.getHost(), address.getPort()).map(ResolvedServerAddress::from);
    }

    /** The route target for {@code host}, keeping {@code host} as the address's host name. */
    Optional<InetSocketAddress> fallbackAddress(String host, int port) {
        if (!enabled.getAsBoolean()) {
            return Optional.empty();
        }
        return decider.decide(host, port).flatMap(route -> {
            try {
                // getByAddress with a name does no DNS; the name keeps the handshake host and lets
                // a later rewrite() route by name instead of seeing an IP literal
                InetAddress named = InetAddress.getByAddress(host, route.target().getAddress());
                return Optional.of(new InetSocketAddress(named, route.port()));
            } catch (UnknownHostException e) {
                return Optional.empty(); // only for a malformed address length; cannot happen for IPv4
            }
        });
    }

    /**
     * Starts the helper early for the first server in {@code servers} that is ours, on one daemon
     * thread; returns at once. Skipped when Cattail is disabled, the platform is unsupported, or a
     * previous early start is still deciding.
     */
    public void earlyStartAsync(List<ServerAddress> servers) {
        if (servers.isEmpty() || !enabled.getAsBoolean() || !platformOk.getAsBoolean()) {
            return;
        }
        if (!earlyStartRunning.compareAndSet(false, true)) {
            return;
        }
        List<ServerAddress> copy = List.copyOf(servers);
        Thread t = new Thread(() -> {
            try {
                for (ServerAddress a : copy) {
                    if (earlyStart(a.getHost(), a.getPort())) {
                        break; // one helper at a time: start it for the first of our servers only
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Cattail early start failed", e);
            } finally {
                earlyStartRunning.set(false);
            }
        }, "cattail-early-start");
        t.setDaemon(true);
        t.start();
    }

    /** Starts the helper in the background when {@code host} is ours; true if it was. Blocks on DNS. */
    boolean earlyStart(String host, int port) {
        if (!enabled.getAsBoolean() || !platformOk.getAsBoolean()) {
            return false;
        }
        Optional<Route> route = decider.decide(host, port);
        // a server-list refresh must never take the helper away from another route
        route.ifPresent(gateway::ensureStartingIfIdleOrSame);
        return route.isPresent();
    }

    /**
     * The route for {@code host} as already decided (by a server-list ping or a join), without any
     * DNS or TXT lookup: safe on the render thread. Empty when Cattail is disabled, the platform has
     * no helper, or the host has not been decided yet.
     */
    public Optional<Route> cachedRoute(String host, int port) {
        if (!enabled.getAsBoolean() || !platformOk.getAsBoolean()) {
            return Optional.empty();
        }
        return decider.cached(host, port);
    }

    /** Whether {@code host} is a Cattail server, from the cache only (see {@link #cachedRoute}). */
    public boolean isOurs(String host, int port) {
        return cachedRoute(host, port).isPresent();
    }

    /** Whether the helper is READY for {@code route} right now. */
    public boolean helperReadyFor(Route route) {
        return gateway.readyFor(route);
    }

    /** The outcome of the current connect attempt, if any. */
    public Optional<Status> lastStatus() {
        return Optional.ofNullable(lastStatus);
    }

    /** Forgets the last outcome; called when a new connect attempt begins. */
    public synchronized void clearStatus() {
        generation.incrementAndGet();
        lastStatus = null;
    }

    private InetSocketAddress route(InetSocketAddress original, long gen) {
        if (!enabled.getAsBoolean()) {
            setStatus(gen, new Status("not-ours", "", false));
            return original;
        }
        Optional<Route> decided = decider.decide(original.getHostString(), original.getPort());
        if (decided.isEmpty()) {
            setStatus(gen, new Status("not-ours", "", false));
            return original;
        }
        Route route = decided.get();
        if (!platformOk.getAsBoolean()) {
            LOGGER.info("{} is a Cattail server, but this jar has no helper for this OS and processor", route.host());
            setStatus(gen, new Status("unsupported-platform", "", true));
            return original;
        }
        String target = route.target().getHostAddress();
        Optional<InetSocketAddress> loopback;
        if (gen == NO_STATUS) {
            // server-list ping: only an explicit join may switch the one helper to another route
            if (!gateway.ensureStartingIfIdleOrSame(route)) {
                LOGGER.debug("{}: helper busy with another route, pinging directly", route.host());
                return original;
            }
            loopback = gateway.awaitLoopback(route);
        } else {
            setStatus(gen, new Status("starting", target, true));
            gateway.ensureStartingAsync(route);
            loopback = gateway.loopbackFor(route);
        }
        if (loopback.isPresent()) {
            setStatus(gen, new Status("connected", target, true));
            return new InetSocketAddress(HelperManager.LOOPBACK_V4, loopback.get().getPort());
        }
        String reason = gateway.failureReason().orElse("unknown");
        LOGGER.warn("Cattail helper not usable for {} ({}), connecting directly", route.host(), reason);
        setStatus(gen, failureStatus(reason));
        return original;
    }

    /**
     * The status line for a helper failure: a deployment problem has its own text, the key of a
     * rejected file as its arg; every other reason shows raw under {@code helper-failed}.
     */
    private static Status failureStatus(String reason) {
        if (reason.startsWith(HelperStartException.DEPLOYMENT_INVALID)) {
            String key = reason.substring(HelperStartException.DEPLOYMENT_INVALID.length());
            return new Status(DEPLOYMENT_INVALID, key, true);
        }
        return switch (reason) {
            case HelperStartException.NOT_CONFIGURED, HelperStartException.KEY_EXPIRED -> new Status(reason, "", true);
            default -> new Status("helper-failed", reason, true);
        };
    }

    /** Records {@code s} unless {@code gen} is {@link #NO_STATUS} or a newer attempt has begun since. */
    private synchronized void setStatus(long gen, Status s) {
        if (gen != NO_STATUS && gen == generation.get()) {
            lastStatus = s;
        }
    }

    private static RouteDecider productionDecider(Map<String, InetAddress> forcedHosts) {
        return new RouteDecider(new JndiTxtRecords(), Redirector::resolveIpv4, forcedHosts);
    }

    /** First IPv4 address of {@code host}; empty when none or on any failure. Never throws. */
    private static Optional<InetAddress> resolveIpv4(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a instanceof Inet4Address) {
                    return Optional.of(a);
                }
            }
        } catch (UnknownHostException | RuntimeException e) {
            // not resolvable: RouteDecider falls back to the TXT target
        }
        return Optional.empty();
    }

    /** Production {@link HelperGateway} over the session-wide {@link HelperManager}. */
    private static final class ManagerGateway implements HelperGateway {
        @Override
        public void ensureStartingAsync(Route r) {
            HelperManager.get().ensureStartingAsync(r);
        }

        @Override
        public Optional<InetSocketAddress> loopbackFor(Route r) {
            return HelperManager.get().loopbackFor(r);
        }

        @Override
        public boolean ensureStartingIfIdleOrSame(Route r) {
            return HelperManager.get().ensureStartingIfIdleOrSame(r);
        }

        @Override
        public Optional<InetSocketAddress> awaitLoopback(Route r) {
            return HelperManager.get().awaitLoopback(r);
        }

        @Override
        public HelperStatus status() {
            return HelperManager.get().status();
        }

        @Override
        public boolean readyFor(Route r) {
            return HelperManager.get().isReadyFor(r);
        }

        @Override
        public Optional<String> failureReason() {
            return HelperManager.get().failureReason();
        }
    }
}
