package net.kuching.cattail.dns;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RouteDeciderTest {

    /** Fake TXT source that returns fixed records (or throws) and counts calls. */
    private static final class FakeTxt implements TxtRecords {
        private final List<String> records;
        private final boolean fail;
        private int failuresLeft;
        final List<String> calls = new ArrayList<>();

        FakeTxt(List<String> records) {
            this.records = records;
            this.fail = false;
        }

        FakeTxt(boolean fail) {
            this.records = List.of();
            this.fail = fail;
        }

        /** Throws on the first {@code failures} calls, then returns {@code records}. */
        FakeTxt(List<String> records, int failures) {
            this.records = records;
            this.fail = false;
            this.failuresLeft = failures;
        }

        @Override
        public List<String> lookup(String host) throws IOException {
            calls.add(host);
            if (fail || failuresLeft > 0) {
                failuresLeft--;
                throw new IOException("simulated DNS failure");
            }
            return records;
        }
    }

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal); // literal only: no DNS query
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    private static Function<String, Optional<InetAddress>> resolvesTo(String literal) {
        return host -> Optional.of(ip(literal));
    }

    private static Function<String, Optional<InetAddress>> resolvesNothing() {
        return host -> Optional.empty();
    }

    @Test
    void decidesOurHost() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());

        Optional<Route> r = d.decide("play.example.com", 25565);

        assertTrue(r.isPresent());
        assertEquals(ip("100.101.102.103"), r.get().target());
        assertEquals(25565, r.get().port());
        assertEquals(25565, r.get().voicePort());
    }

    @Test
    void txtFieldOrderAndSpaces() {
        FakeTxt txt = new FakeTxt(List.of(" target=100.101.102.103 ;cattail=1; foo=bar"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());

        Optional<Route> r = d.decide("play.example.com", 25565);

        assertTrue(r.isPresent());
        assertEquals(ip("100.101.102.103"), r.get().target());
    }

    @Test
    void uppercaseHostAndExplicitPort() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());

        Optional<Route> r = d.decide("PLAY.EXAMPLE.COM", 25566);

        assertTrue(r.isPresent());
        assertEquals(25566, r.get().port());
        assertEquals(1, txt.calls.size());

        Optional<Route> again = d.decide("play.example.com", 25565);

        assertTrue(again.isPresent());
        assertEquals(25565, again.get().port());
        assertEquals(1, txt.calls.size(), "second decide must be a cache hit");
    }

    @Test
    void noTxtNotOurs() {
        FakeTxt txt = new FakeTxt(List.of());
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());

        assertEquals(Optional.empty(), d.decide("play.example.com", 25565));
    }

    @Test
    void txtButPublicIp() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("203.0.113.5"), Map.of());

        assertEquals(Optional.empty(), d.decide("play.example.com", 25565));
    }

    @Test
    void resolverEmptyFallsBackToTarget() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, resolvesNothing(), Map.of());

        Optional<Route> r = d.decide("play.example.com", 25565);

        assertTrue(r.isPresent());
        assertEquals(ip("100.101.102.103"), r.get().target());
    }

    @Test
    void voicePortParsed() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103; voice=24454"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());
        assertEquals(24454, d.decide("play.example.com", 25565).orElseThrow().voicePort());

        FakeTxt bad = new FakeTxt(List.of("cattail=1; target=100.101.102.103; voice=abc"));
        RouteDecider d2 = new RouteDecider(bad, resolvesTo("100.101.102.103"), Map.of());
        assertEquals(25565, d2.decide("play.example.com", 25565).orElseThrow().voicePort());
    }

    @Test
    void voiceDefaultsToTheGamePortPerCall() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());
        assertEquals(25565, d.decide("play.example.com", 25565).orElseThrow().voicePort());
        assertEquals(25570, d.decide("play.example.com", 25570).orElseThrow().voicePort());
        assertEquals(1, txt.calls.size(), "one cached decision serves both ports");
        assertEquals(25570, d.cached("play.example.com", 25570).orElseThrow().voicePort());
    }

    @Test
    void voiceZeroTurnsTheRelayOff() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103; voice=0"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());
        assertEquals(0, d.decide("play.example.com", 25565).orElseThrow().voicePort());
    }

    @Test
    void voiceFieldToleratesSpacesAndCase() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103; VOICE = 24454 "));
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());
        assertEquals(24454, d.decide("play.example.com", 25565).orElseThrow().voicePort());
    }

    @Test
    void invalidVoiceValuesBehaveAsAbsent() {
        for (String bad : List.of("voice=65536", "voice=-1", "voice=", "voice=24454x")) {
            FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103; " + bad));
            RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());
            assertEquals(25565, d.decide("play.example.com", 25565).orElseThrow().voicePort(), bad);
        }
    }

    @Test
    void forcedHostWins() {
        FakeTxt txt = new FakeTxt(true);
        RouteDecider d = new RouteDecider(txt, resolvesNothing(),
                Map.of("test.local", ip("100.101.102.103")));

        Optional<Route> r = d.decide("test.local", 25570);

        assertTrue(r.isPresent());
        assertEquals(ip("100.101.102.103"), r.get().target());
        assertEquals(25570, r.get().port());
        assertEquals(25570, r.get().voicePort());
        assertEquals(0, txt.calls.size());
    }

    @Test
    void literalIpAndLocalhostNever() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());

        assertEquals(Optional.empty(), d.decide("100.101.102.103", 25565));
        assertEquals(Optional.empty(), d.decide("localhost", 25565));
        assertEquals(0, txt.calls.size());
    }

    @Test
    void dnsFailureIsNotOurs() {
        FakeTxt txt = new FakeTxt(true);
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());

        assertEquals(Optional.empty(), d.decide("play.example.com", 25565));
    }

    @Test
    void dnsFailureIsNotCached() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"), 1);
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());

        assertEquals(Optional.empty(), d.decide("play.example.com", 25565));
        Optional<Route> r = d.decide("play.example.com", 25565);

        assertTrue(r.isPresent(), "a failed lookup must not be cached");
        assertEquals(ip("100.101.102.103"), r.get().target());
        assertEquals(2, txt.calls.size());
    }

    @Test
    void throwingResolverFallsBackToTarget() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, host -> {
            throw new IllegalStateException("resolver blew up");
        }, Map.of());

        Optional<Route> r = assertDoesNotThrow(() -> d.decide("play.example.com", 25565));

        assertTrue(r.isPresent());
        assertEquals(ip("100.101.102.103"), r.get().target());
    }

    @Test
    void nullResolverResultFallsBackToTarget() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, host -> null, Map.of());

        Optional<Route> r = assertDoesNotThrow(() -> d.decide("play.example.com", 25565));

        assertTrue(r.isPresent());
        assertEquals(ip("100.101.102.103"), r.get().target());
    }

    @Test
    void ipv6ResolverResultFallsBackToTarget() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("fd7a:115c:a1e0::1"), Map.of());

        Optional<Route> r = d.decide("play.example.com", 25565);

        assertTrue(r.isPresent());
        assertEquals(ip("100.101.102.103"), r.get().target());
    }

    @Test
    void nullTxtListIsNotOurs() {
        RouteDecider d = new RouteDecider(host -> null, resolvesTo("100.101.102.103"), Map.of());

        assertEquals(Optional.empty(),
                assertDoesNotThrow(() -> d.decide("play.example.com", 25565)));
    }

    @Test
    void publicIpNeverQueriesTxt() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("203.0.113.5"), Map.of());

        assertEquals(Optional.empty(), d.decide("play.example.com", 25565));
        assertEquals(Optional.empty(), d.decide("play.example.com", 25565));
        assertEquals(0, txt.calls.size(), "a public A record must never trigger a TXT query");
    }

    @Test
    void cgnatIpQueriesTxt() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());

        assertTrue(d.decide("play.example.com", 25565).isPresent());
        assertEquals(List.of("play.example.com"), txt.calls);
    }

    @Test
    void emptyResolverQueriesTxt() {
        FakeTxt txt = new FakeTxt(List.of());
        RouteDecider d = new RouteDecider(txt, resolvesNothing(), Map.of());

        assertEquals(Optional.empty(), d.decide("play.example.com", 25565));
        assertEquals(1, txt.calls.size());
    }

    @Test
    void cachedIsEmptyForAnUnknownHostWithoutAnyLookup() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103"));
        List<String> resolved = new ArrayList<>();
        RouteDecider d = new RouteDecider(txt, host -> {
            resolved.add(host);
            return Optional.of(ip("100.101.102.103"));
        }, Map.of());

        assertEquals(Optional.empty(), d.cached("play.example.com", 25565));
        assertTrue(resolved.isEmpty(), "cached() must never resolve");
        assertTrue(txt.calls.isEmpty(), "cached() must never query TXT");
    }

    @Test
    void cachedAnswersTheDecidedRouteAfterDecide() {
        FakeTxt txt = new FakeTxt(List.of("cattail=1; target=100.101.102.103; voice=24454"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());

        Optional<Route> decided = d.decide("play.example.com", 25565);
        assertTrue(decided.isPresent());
        assertEquals(decided, d.cached("PLAY.example.com", 25565));
        assertEquals(1, txt.calls.size());
    }

    @Test
    void cachedIsEmptyForADecidedHostThatIsNotOurs() {
        RouteDecider d = new RouteDecider(new FakeTxt(List.of("v=spf1")), resolvesTo("100.101.102.103"), Map.of());

        assertEquals(Optional.empty(), d.decide("other.example.com", 25565));
        assertEquals(Optional.empty(), d.cached("other.example.com", 25565));
    }

    @Test
    void cachedAnswersForcedHostsWithoutAnyLookup() {
        FakeTxt txt = new FakeTxt(true);
        RouteDecider d = new RouteDecider(txt, resolvesNothing(),
                Map.of("mc.example.com", ip("100.101.102.103")));

        assertEquals(Optional.of(new Route("mc.example.com", ip("100.101.102.103"), 25565, 25565)),
                d.cached("mc.example.com", 25565));
        assertTrue(txt.calls.isEmpty());
    }
    @Test
    void legacyTslinkKeyStillRoutes() {
        FakeTxt txt = new FakeTxt(List.of("tslink=1; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());

        assertTrue(d.decide("play.example.com", 25565).isPresent());
    }

    @Test
    void txtKeyWithAnotherValueIsNotRouted() {
        FakeTxt txt = new FakeTxt(List.of("cattail=0; target=100.101.102.103",
                "tslink=yes; target=100.101.102.103"));
        RouteDecider d = new RouteDecider(txt, resolvesTo("100.101.102.103"), Map.of());

        assertTrue(d.decide("play.example.com", 25565).isEmpty());
    }
}
