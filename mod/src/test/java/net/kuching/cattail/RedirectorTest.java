package net.kuching.cattail;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kuching.cattail.dns.Route;
import net.kuching.cattail.dns.RouteDecider;
import net.kuching.cattail.helper.HelperStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class RedirectorTest {

    private static final String HOST = "mc.example.com";

    /** Records calls and answers {@code loopbackFor} with a fixed result. */
    private static class FakeGateway implements Redirector.HelperGateway {
        final List<Route> started = new ArrayList<>();
        final List<Route> asked = new ArrayList<>();
        private final Optional<InetSocketAddress> loopback;
        private final String failure;

        FakeGateway(Optional<InetSocketAddress> loopback, String failure) {
            this.loopback = loopback;
            this.failure = failure;
        }

        /** Route the fake helper is serving (null = idle); pings attach only when idle or the same. */
        Route serving;
        final List<Route> attached = new ArrayList<>();
        final List<Route> awaited = new ArrayList<>();

        @Override
        public void ensureStartingAsync(Route r) {
            started.add(r);
            serving = r;
        }

        @Override
        public Optional<InetSocketAddress> loopbackFor(Route r) {
            asked.add(r);
            return loopback;
        }

        @Override
        public boolean ensureStartingIfIdleOrSame(Route r) {
            if (serving != null && !(serving.target().equals(r.target()) && serving.port() == r.port()
                    && serving.voicePort() == r.voicePort())) {
                return false;
            }
            attached.add(r);
            serving = r;
            return true;
        }

        @Override
        public Optional<InetSocketAddress> awaitLoopback(Route r) {
            awaited.add(r);
            return loopback;
        }

        @Override
        public boolean readyFor(Route r) {
            return loopback.isPresent() && r.equals(serving);
        }

        @Override
        public HelperStatus status() {
            return loopback.isPresent() ? HelperStatus.READY : HelperStatus.FAILED;
        }

        @Override
        public Optional<String> failureReason() {
            return Optional.ofNullable(failure);
        }
    }

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal); // literal only: no DNS query
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    /** A decider whose TXT says {@code records} and whose resolver answers {@code resolved}. */
    private static RouteDecider decider(List<String> records, Optional<InetAddress> resolved) {
        return new RouteDecider(host -> records, host -> resolved, Map.of());
    }

    private static RouteDecider ours() {
        return decider(List.of("cattail=1"), Optional.of(ip("100.101.102.103")));
    }

    private static InetSocketAddress original() {
        return InetSocketAddress.createUnresolved(HOST, 25565);
    }

    @Test
    void rewriteReturnsOriginalWhenNotOurs() {
        FakeGateway gw = new FakeGateway(Optional.empty(), null);
        Redirector r = new Redirector(decider(List.of("v=spf1"), Optional.of(ip("100.101.102.103"))),
                gw, () -> true, () -> true);

        InetSocketAddress in = original();
        assertSame(in, r.rewrite(in));
        assertTrue(gw.started.isEmpty());
        assertTrue(gw.asked.isEmpty());
        Redirector.Status s = r.lastStatus().orElseThrow();
        assertEquals("not-ours", s.key());
        assertFalse(s.visible());
    }

    @Test
    void rewriteReturnsLoopbackWhenReady() {
        InetSocketAddress loop = new InetSocketAddress(InetAddress.getLoopbackAddress(), 51234);
        FakeGateway gw = new FakeGateway(Optional.of(loop), null);
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        InetSocketAddress out = r.rewrite(original());
        assertArrayEquals(new byte[] {127, 0, 0, 1}, out.getAddress().getAddress(),
                "explicit 127.0.0.1 even when getLoopbackAddress() is ::1 (preferIPv6Addresses)");
        assertEquals(51234, out.getPort());
        assertEquals(1, gw.started.size());
        assertEquals(ip("100.101.102.103"), gw.started.get(0).target());
        assertEquals(25565, gw.started.get(0).port());
        Redirector.Status s = r.lastStatus().orElseThrow();
        assertEquals("connected", s.key());
        assertEquals("100.101.102.103", s.arg());
        assertTrue(s.visible());
    }

    @Test
    void rewriteTargetCarriesTheLiteralLoopbackName() {
        FakeGateway gw = new FakeGateway(Optional.of(new InetSocketAddress(ip("127.0.0.1"), 51234)), null);
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        InetSocketAddress out = r.rewrite(original());

        // Plasmo Voice re-resolves getHostName() of the game connection; a reverse lookup would
        // give "localhost" (::1 under preferIPv6Addresses) and miss the helper.
        assertEquals("127.0.0.1", out.getHostName());
        assertEquals("127.0.0.1", out.getAddress().getHostName());
        assertArrayEquals(new byte[] {127, 0, 0, 1}, out.getAddress().getAddress());
    }

    @Test
    void helperIsStartedWithTheGamePortAsVoicePortByDefault() {
        FakeGateway gw = new FakeGateway(Optional.of(new InetSocketAddress(ip("127.0.0.1"), 51234)), null);
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        r.rewrite(original());

        assertEquals(25565, gw.started.get(0).voicePort());
    }

    @Test
    void rewriteWhenHelperFailedReturnsOriginal() {
        FakeGateway gw = new FakeGateway(Optional.empty(), "ready-timeout");
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        InetSocketAddress in = original();
        assertSame(in, r.rewrite(in));
        Redirector.Status s = r.lastStatus().orElseThrow();
        assertEquals("helper-failed", s.key());
        assertEquals("ready-timeout", s.arg());
        assertTrue(s.visible());
    }

    /** Rewrites through a helper that failed with {@code reason}; the join goes to the original address. */
    private static Redirector rewriteWithFailure(String reason) {
        Redirector r = new Redirector(ours(), new FakeGateway(Optional.empty(), reason), () -> true, () -> true);
        InetSocketAddress in = original();
        assertSame(in, r.rewrite(in));
        return r;
    }

    @Test
    void notConfiguredHasItsOwnStatusKey() {
        assertEquals(Optional.of(new Redirector.Status("not-configured", "", true)),
                rewriteWithFailure("not-configured").lastStatus());
    }

    @Test
    void deploymentInvalidCarriesTheKey() {
        assertEquals(Optional.of(new Redirector.Status("deployment-invalid", "tag", true)),
                rewriteWithFailure("deployment-invalid:tag").lastStatus());
    }

    @Test
    void keyExpiredHasItsOwnStatusKey() {
        assertEquals(Optional.of(new Redirector.Status("key-expired", "", true)),
                rewriteWithFailure("key-expired").lastStatus());
    }

    @Test
    void resolveFallbackUsesTxtTarget() {
        Redirector r = new Redirector(
                decider(List.of("cattail=1; target=100.127.255.255"), Optional.empty()),
                new FakeGateway(Optional.empty(), null), () -> true, () -> true);

        InetSocketAddress out = r.fallbackAddress(HOST, 25570).orElseThrow();
        assertEquals("100.127.255.255", out.getAddress().getHostAddress());
        assertEquals(25570, out.getPort());
        // keeps the typed host name, so the handshake and a later rewrite() see the name
        assertEquals(HOST, out.getHostString());
    }

    @Test
    void rewriteOnUnsupportedPlatformReturnsOriginal() {
        FakeGateway gw = new FakeGateway(Optional.of(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 51234)), null);
        Redirector r = new Redirector(ours(), gw, () -> false, () -> true);

        InetSocketAddress in = original();
        assertSame(in, r.rewrite(in));
        assertTrue(gw.started.isEmpty());
        assertTrue(gw.asked.isEmpty());
        Redirector.Status s = r.lastStatus().orElseThrow();
        assertEquals("unsupported-platform", s.key());
        assertTrue(s.visible());
    }

    @Test
    void unsupportedPlatformIsShownEvenUnroutedWithItsLangText() throws IOException {
        assertTrue(new Redirector.Status("unsupported-platform", "", false).visible(), "in VISIBLE_UNROUTED");
        try (InputStream in = Redirector.class.getResourceAsStream("/assets/cattail/lang/en_us.json")) {
            assertNotNull(in, "en_us.json");
            JsonObject lang =
                    JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("Cattail supports Windows x64, Linux x64 and macOS on Apple silicon only",
                    lang.get("cattail.status.unsupported-platform").getAsString());
        }
    }

    @Test
    void preparedRewriteIsReusedForTheSameAddress() {
        InetSocketAddress loop = new InetSocketAddress(InetAddress.getLoopbackAddress(), 51234);
        FakeGateway gw = new FakeGateway(Optional.of(loop), null);
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        InetSocketAddress in = original();
        r.prepare(in);
        assertEquals(1, gw.asked.size());
        assertEquals(51234, r.connectAddress(in).getPort());
        assertEquals(1, gw.asked.size()); // no second, possibly blocking, helper call
    }

    @Test
    void disabledNeverRoutes() {
        FakeGateway gw = new FakeGateway(Optional.empty(), null);
        Redirector r = new Redirector(ours(), gw, () -> true, () -> false);

        InetSocketAddress in = original();
        assertSame(in, r.rewrite(in));
        assertTrue(r.fallbackAddress(HOST, 25565).isEmpty());
        assertTrue(gw.started.isEmpty());
        assertEquals("not-ours", r.lastStatus().orElseThrow().key());
    }

    @Test
    void lateStatusOfCancelledAttemptIsDropped() throws Exception {
        CountDownLatch inLoopback = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        InetSocketAddress loop = new InetSocketAddress(InetAddress.getLoopbackAddress(), 51234);
        Redirector.HelperGateway gw = new FakeGateway(Optional.empty(), "ready-timeout") {
            @Override
            public Optional<InetSocketAddress> loopbackFor(Route r) {
                if (calls.incrementAndGet() == 1) { // attempt N: blocks until released, then succeeds
                    inLoopback.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Optional.of(loop);
                }
                return Optional.empty(); // attempt N+1: helper failed
            }
        };
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        Thread attemptN = new Thread(() -> r.prepare(original())); // captures generation N
        attemptN.start();
        assertTrue(inLoopback.await(10, TimeUnit.SECONDS));

        r.clearStatus(); // Cancel + new attempt: generation N+1
        r.prepare(original()); // attempt N+1 records helper-failed
        assertEquals("helper-failed", r.lastStatus().orElseThrow().key());

        release.countDown(); // attempt N finishes late and would write "connected"
        attemptN.join(10_000);
        assertFalse(attemptN.isAlive());
        Redirector.Status s = r.lastStatus().orElseThrow();
        assertEquals("helper-failed", s.key());
        assertEquals("ready-timeout", s.arg());
    }

    @Test
    void connectAddressMismatchReturnsAddressUnchangedWithoutBlocking() {
        AtomicInteger dns = new AtomicInteger();
        RouteDecider counting = new RouteDecider(host -> {
            dns.incrementAndGet();
            return List.of("cattail=1");
        }, host -> {
            dns.incrementAndGet();
            return Optional.of(ip("100.101.102.103"));
        }, Map.of());
        FakeGateway gw = new FakeGateway(Optional.of(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 51234)), null);
        Redirector r = new Redirector(counting, gw, () -> true, () -> true);

        InetSocketAddress other = InetSocketAddress.createUnresolved("other.example.com", 25565);
        assertSame(other, r.connectAddress(other)); // nothing prepared at all
        assertEquals(0, dns.get());
        assertTrue(gw.started.isEmpty());
        assertTrue(gw.asked.isEmpty());

        r.prepare(original());
        int dnsAfterPrepare = dns.get();
        int askedAfterPrepare = gw.asked.size();
        int startedAfterPrepare = gw.started.size();
        assertSame(other, r.connectAddress(other)); // prepared for a different address
        assertEquals(dnsAfterPrepare, dns.get());
        assertEquals(askedAfterPrepare, gw.asked.size());
        assertEquals(startedAfterPrepare, gw.started.size());
    }

    private static Route otherRoute() {
        return new Route("other.example.com", ip("100.1.2.3"), 25565, 0);
    }

    @Test
    void pingForAnotherRouteWhileServingReturnsOriginal() {
        FakeGateway gw = new FakeGateway(Optional.of(new InetSocketAddress(ip("127.0.0.1"), 51234)), null);
        gw.serving = otherRoute();
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        InetSocketAddress in = original();
        assertSame(in, r.rewriteQuiet(in));
        assertTrue(gw.started.isEmpty(), "a ping must never switch the helper");
        assertTrue(gw.attached.isEmpty());
        assertTrue(gw.awaited.isEmpty());
        assertTrue(gw.asked.isEmpty());
        assertEquals(otherRoute(), gw.serving);
        assertTrue(r.lastStatus().isEmpty(), "pings leave the status line alone");
    }

    @Test
    void pingForTheSameRouteReturnsLoopback() {
        FakeGateway gw = new FakeGateway(Optional.of(new InetSocketAddress(ip("127.0.0.1"), 51234)), null);
        gw.serving = new Route(HOST, ip("100.101.102.103"), 25565, 25565);
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        InetSocketAddress out = r.rewriteQuiet(original());

        assertArrayEquals(new byte[] {127, 0, 0, 1}, out.getAddress().getAddress());
        assertEquals(51234, out.getPort());
        assertEquals(1, gw.attached.size());
        assertEquals(1, gw.awaited.size());
        assertTrue(gw.started.isEmpty());
        assertTrue(gw.asked.isEmpty());
    }

    @Test
    void pingWhenIdleStartsTheHelper() {
        FakeGateway gw = new FakeGateway(Optional.of(new InetSocketAddress(ip("127.0.0.1"), 51234)), null);
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        assertEquals(51234, r.rewriteQuiet(original()).getPort());
        assertEquals(1, gw.attached.size());
    }

    @Test
    void joinForAnotherRouteSwitches() {
        FakeGateway gw = new FakeGateway(Optional.of(new InetSocketAddress(ip("127.0.0.1"), 51234)), null);
        gw.serving = otherRoute();
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        InetSocketAddress out = r.rewrite(original());

        assertEquals(51234, out.getPort());
        assertEquals(1, gw.started.size());
        assertEquals(ip("100.101.102.103"), gw.serving.target());
        assertEquals("connected", r.lastStatus().orElseThrow().key());
    }

    @Test
    void earlyStartDoesNotSwitchABusyHelper() {
        FakeGateway gw = new FakeGateway(Optional.empty(), null);
        gw.serving = otherRoute();
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        assertTrue(r.earlyStart(HOST, 25565), "the host is ours");
        assertTrue(gw.started.isEmpty());
        assertTrue(gw.attached.isEmpty());
        assertEquals(otherRoute(), gw.serving);
    }

    @Test
    void earlyStartWhenIdleStartsTheHelper() {
        FakeGateway gw = new FakeGateway(Optional.empty(), null);
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        assertTrue(r.earlyStart(HOST, 25565));
        assertEquals(1, gw.attached.size());
        assertTrue(gw.started.isEmpty());
    }

    @Test
    void testJvmPrefersIpv6SoTheLoopbackAssertionsMeanSomething() {
        assertEquals("true", System.getProperty("java.net.preferIPv6Addresses"));
        assertEquals(16, InetAddress.getLoopbackAddress().getAddress().length);
    }

    @Test
    void isOursIsFalseWhenDisabled() {
        Redirector r = new Redirector(ours(), new FakeGateway(Optional.empty(), null), () -> true, () -> false);

        r.rewriteQuiet(original());
        assertFalse(r.isOurs(HOST, 25565));
    }

    @Test
    void isOursIsFalseForAnUnknownHostWithoutAnyLookup() {
        AtomicInteger lookups = new AtomicInteger();
        RouteDecider counting = new RouteDecider(host -> {
            lookups.incrementAndGet();
            return List.of("cattail=1");
        }, host -> {
            lookups.incrementAndGet();
            return Optional.of(ip("100.101.102.103"));
        }, Map.of());
        Redirector r = new Redirector(counting, new FakeGateway(Optional.empty(), null), () -> true, () -> true);

        assertFalse(r.isOurs(HOST, 25565));
        assertEquals(0, lookups.get(), "isOurs runs on the render thread: no DNS, no TXT");
    }

    @Test
    void isOursIsTrueAfterAServerListPing() {
        InetSocketAddress loop = new InetSocketAddress(InetAddress.getLoopbackAddress(), 51234);
        FakeGateway gw = new FakeGateway(Optional.of(loop), null);
        Redirector r = new Redirector(ours(), gw, () -> true, () -> true);

        r.rewriteQuiet(original());
        assertTrue(r.isOurs(HOST, 25565));
        Route route = r.cachedRoute(HOST, 25565).orElseThrow();
        assertEquals(ip("100.101.102.103"), route.target());
        assertTrue(r.helperReadyFor(route));
    }

    @Test
    void isOursIsFalseOnAnUnsupportedPlatform() {
        Redirector r = new Redirector(ours(), new FakeGateway(Optional.empty(), null), () -> false, () -> true);

        r.rewriteQuiet(original());
        assertFalse(r.isOurs(HOST, 25565), "no helper there, so no tunnel to label");
    }
}
