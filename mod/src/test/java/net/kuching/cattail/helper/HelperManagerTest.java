package net.kuching.cattail.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kuching.cattail.deploy.Deployment;
import net.kuching.cattail.deploy.DeploymentSettings;
import net.kuching.cattail.dns.Route;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class HelperManagerTest {

    private static final byte[] EXE = "MZ bundled helper".getBytes(StandardCharsets.US_ASCII);
    private static final String KEY = "tskey-client-EXAMPLE";

    @TempDir
    Path gameDir;

    @TempDir
    Path scriptDir;

    private Path script;
    private FakeFactory factory;
    private HelperManager manager;
    private final AtomicBoolean forceRelay = new AtomicBoolean();
    /** "probe" / "start" in the order the probe runner and the process factory were called. */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final List<ProbeCall> probeCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<String> probeLines = Collections.synchronizedList(new ArrayList<>());
    /** FAKE_MODE for the probe run of fake-helper.sh. */
    private volatile String probeMode = "probe";
    /** When set, replaces the probe timeout the manager asks for. */
    private volatile Duration probeTimeoutOverride;
    /** What the manager's deployment supplier answers: a configured client secret unless a test changes it. */
    private volatile Deployment deployment = new Deployment.Configured(settings(KEY));
    /** What the manager's clock supplier answers. */
    private volatile LocalDate today = LocalDate.of(2026, 10, 21);
    /** False: the probe runner and the process factory record their calls but run nothing (any OS). */
    private volatile boolean launchFakes = true;

    private static final String TARGETS = "100.101.102.103:22,100.1.2.3:22,100.101.102.103:25565";

    /** One recorded call to the probe runner. */
    record ProbeCall(Path exe, Map<String, String> env, List<String> baseArgs, String targets, Duration timeout) {}

    /** Runs fake-helper.sh in a probe mode instead of the exe and records every call. */
    private final HelperManager.ProbeRunner probeRunner = (exe, env, baseArgs, targets, timeout, line) -> {
        events.add("probe");
        probeCalls.add(new ProbeCall(exe, Map.copyOf(env), List.copyOf(baseArgs), targets, timeout));
        if (!launchFakes) {
            return Optional.empty();
        }
        Map<String, String> e = new HashMap<>(env);
        e.put("FAKE_MODE", probeMode);
        Duration t = probeTimeoutOverride != null ? probeTimeoutOverride : timeout;
        return HelperProbe.runOnce(script, e, baseArgs, targets, t, l -> {
            probeLines.add(l);
            line.accept(l);
        });
    };

    /** One recorded call to the process factory. */
    record Call(Path exe, Map<String, String> env, List<String> args, Duration readyTimeout) {}

    /** Launches fake-helper.sh instead of the exe and records every call. */
    final class FakeFactory implements HelperManager.ProcessFactory {
        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        final List<HelperProcess> started = Collections.synchronizedList(new ArrayList<>());
        /** Extra env for the fake (FAKE_MODE etc.) per start, by start index; default none. */
        final Map<Integer, Map<String, String>> fakeEnv = Collections.synchronizedMap(new HashMap<>());
        /** Number of fake helpers alive when each start call began. */
        final List<Long> aliveAtStart = Collections.synchronizedList(new ArrayList<>());

        @Override
        public HelperProcess start(Path exe, Map<String, String> env, List<String> args, Duration readyTimeout)
                throws IOException {
            int index = calls.size();
            events.add("start");
            aliveAtStart.add(aliveFakes());
            calls.add(new Call(exe, Map.copyOf(env), List.copyOf(args), readyTimeout));
            if (!launchFakes) {
                throw new HelperStartException("not-launched", OptionalLong.empty()); // a WARN, no stack trace
            }
            Map<String, String> e = new HashMap<>(env);
            e.putAll(fakeEnv.getOrDefault(index, Map.of()));
            HelperProcess p = HelperProcess.start(script, e, args, readyTimeout);
            started.add(p);
            return p;
        }
    }

    /** Live fake-helper shells started by this JVM (their `sleep` children excluded). */
    private long aliveFakes() {
        String path = script.toString();
        return ProcessHandle.current().descendants()
                .filter(ProcessHandle::isAlive)
                .filter(h -> h.info().commandLine().map(c -> c.contains(path)).orElse(false))
                .count();
    }

    /** Samples {@link #aliveFakes()} every few ms until stopped and keeps the maximum. */
    private final class AlivePoller implements AutoCloseable {
        private final java.util.concurrent.atomic.AtomicLong max = new java.util.concurrent.atomic.AtomicLong();
        private volatile boolean running = true;
        private final Thread thread = new Thread(() -> {
            while (running) {
                max.accumulateAndGet(aliveFakes(), Math::max);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        AlivePoller() {
            thread.setDaemon(true);
            thread.start();
        }

        long max() {
            return max.get();
        }

        @Override
        public void close() throws InterruptedException {
            running = false;
            thread.join(5_000);
        }
    }

    private void awaitAliveFakes(long n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (aliveFakes() != n && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(n, aliveFakes());
    }

    private static Route route(String ip, int port, int voice) throws Exception {
        return new Route("mc.example.com", InetAddress.getByName(ip), port, voice);
    }

    private HelperManager.HelperBundle bundle() throws Exception {
        return new HelperManager.HelperBundle(() -> new ByteArrayInputStream(EXE), HelperInstallerTest.sha256(EXE),
                "cattail-helper");
    }

    /** Deployment settings with tag {@code tag:example}, the default specs prefix and no expiry date. */
    private static DeploymentSettings settings(String authKey, Optional<String> probe) {
        return new DeploymentSettings(authKey, Optional.empty(), "tag:example", DeploymentSettings.DEFAULT_PREFIX, probe);
    }

    private static DeploymentSettings settings(String authKey) {
        return settings(authKey, Optional.empty());
    }

    /** An auth key whose {@code authkey.expires} is 2026-10-20 (valid through that day). */
    private static DeploymentSettings expiringAuthKey() {
        return new DeploymentSettings("tskey-auth-EXAMPLE", Optional.of(LocalDate.of(2026, 10, 20)), "tag:example",
                DeploymentSettings.DEFAULT_PREFIX, Optional.empty());
    }

    private HelperManager newManager(Optional<HelperManager.HelperBundle> b, Duration budget) {
        return newManager(b, budget, () -> "Alice");
    }

    private HelperManager newManager(Optional<HelperManager.HelperBundle> b, Duration budget,
            java.util.function.Supplier<String> user) {
        return new HelperManager(gameDir, () -> b, () -> deployment, factory, user, forceRelay::get, budget,
                probeRunner, () -> today);
    }

    private static void kill(HelperProcess p) throws Exception {
        p.handle().destroyForcibly();
        p.handle().onExit().get(10, TimeUnit.SECONDS);
    }

    private static void awaitStatus(HelperManager m, HelperStatus want) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (m.status() != want && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(want, m.status());
    }

    @BeforeEach
    void setUp() throws Exception {
        script = FakeHelperScript.copyTo(scriptDir);
        factory = new FakeFactory();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
        for (HelperProcess p : factory.started) {
            p.handle().destroyForcibly();
        }
    }

    @Test
    void hostnameMirrorsTheHelperSanitiser() {
        assertEquals("mc-alice", HelperManager.hostnameFor("Alice"));
        assertEquals("mc-hz-90-x", HelperManager.hostnameFor("Hz_90!!x"));
        assertEquals("mc-player", HelperManager.hostnameFor("___"));
        assertEquals("mc-player", HelperManager.hostnameFor(""));
        assertEquals("mc-player", HelperManager.hostnameFor(null));
        assertEquals("mc-abcdefghijklmnop", HelperManager.hostnameFor("ABCDEFGHIJKLMNOPQRST"));
        assertEquals("mc-abcdefghijklmno", HelperManager.hostnameFor("abcdefghijklmno_pqrst"),
                "a '-' left at the cut is trimmed");
        assertEquals("mc-a-b", HelperManager.hostnameFor("--a__b--"));
    }

    @Test
    void helperVersionFallsBackToDevAndIsPathSafe() {
        assertEquals("dev", HelperManager.helperVersion());
        Manifest m = new Manifest();
        assertEquals("dev", HelperManager.versionFrom(m));
        m.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "0.1.0");
        assertEquals("0.1.0", HelperManager.versionFrom(m));
        m.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "../../evil");
        assertEquals("dev", HelperManager.versionFrom(m));
    }

    @Test
    void missingBundleFailsCleanly() throws Exception {
        manager = newManager(Optional.empty(), Duration.ofSeconds(20));

        assertEquals(Optional.empty(), manager.loopbackFor(route("100.101.102.103", 25565, 0)));
        assertEquals(HelperStatus.FAILED, manager.status());
        assertEquals(Optional.of("helper-missing"), manager.failureReason());
        assertEquals(0, factory.calls.size());
    }

    @Test
    void genericJarFailsAsNotConfigured() throws Exception {
        deployment = new Deployment.Generic();
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        Route r = route("100.101.102.103", 25565, 0);

        manager.ensureStartingAsync(r);
        assertEquals(Optional.empty(), manager.loopbackFor(r));
        assertEquals(HelperStatus.FAILED, manager.status());
        assertEquals(Optional.of("not-configured"), manager.failureReason());
        assertEquals(0, factory.calls.size());
        assertFalse(Files.exists(gameDir.resolve("cattail")), "the helper is not even extracted");
    }

    @Test
    void invalidDeploymentFailsWithTheKey() throws Exception {
        deployment = new Deployment.Invalid("tag");
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        Route r = route("100.101.102.103", 25565, 0);

        manager.ensureStartingAsync(r);
        assertEquals(Optional.empty(), manager.loopbackFor(r));
        assertEquals(HelperStatus.FAILED, manager.status());
        assertEquals(Optional.of("deployment-invalid:tag"), manager.failureReason());
        assertEquals(0, factory.calls.size());
        assertFalse(Files.exists(gameDir.resolve("cattail")), "the helper is not even extracted");
    }

    @Test
    void clientSecretReachesHelperWithOptions() throws Exception {
        launchFakes = false;
        deployment = new Deployment.Configured(settings("tskey-client-EXAMPLE"));
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));

        assertEquals(Optional.empty(), manager.loopbackFor(route("100.101.102.103", 25565, 0)), "nothing launched");

        Call c = factory.calls.get(0);
        assertEquals("tskey-client-EXAMPLE?ephemeral=true&preauthorized=true", c.env().get("TS_AUTHKEY"));
        assertTrue(Collections.indexOfSubList(c.args(), List.of("--tag", "tag:example")) >= 0, c.args().toString());
        assertTrue(c.args().stream().noneMatch(a -> a.contains("tskey")), "secret must never be in argv");
    }

    @Test
    void authKeyReachesHelperUntouched() throws Exception {
        launchFakes = false;
        deployment = new Deployment.Configured(settings("tskey-auth-EXAMPLE"));
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));

        assertEquals(Optional.empty(), manager.loopbackFor(route("100.101.102.103", 25565, 0)), "nothing launched");

        assertEquals("tskey-auth-EXAMPLE", factory.calls.get(0).env().get("TS_AUTHKEY"));
    }

    @Test
    void startReasonMapsOnlyAnAuthFailureAfterExpiry() {
        DeploymentSettings s = expiringAuthKey();
        LocalDate dayAfter = LocalDate.of(2026, 10, 21);

        assertEquals("key-expired", HelperManager.startReason("helper-error:auth", s, dayAfter));
        assertEquals("helper-error:auth", HelperManager.startReason("helper-error:auth", s, LocalDate.of(2026, 10, 20)),
                "the key is valid through its last day");
        assertEquals("helper-error:up-timeout", HelperManager.startReason("helper-error:up-timeout", s, dayAfter));
        assertEquals("ready-timeout", HelperManager.startReason("ready-timeout", s, dayAfter));
        assertEquals("helper-error:auth",
                HelperManager.startReason("helper-error:auth", settings("tskey-auth-EXAMPLE"), LocalDate.of(2099, 1, 1)),
                "no expiry date, no key-expired");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void isReadyForAnswersOnlyForTheServedRouteOnceReady() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        Route r = route("100.101.102.103", 25565, 0);
        Route other = route("100.1.2.3", 25565, 0);
        assertFalse(manager.isReadyFor(r), "nothing started");

        assertTrue(manager.loopbackFor(r).isPresent());
        assertTrue(manager.isReadyFor(r));
        assertFalse(manager.isReadyFor(other));
        assertFalse(manager.isReadyFor(null));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void ensureStartingTwiceStartsOneProcessWithInstalledExeArgsAndEnv() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        factory.fakeEnv.put(0, Map.of("FAKE_DELAY_MS", "300"));
        Route r = route("100.101.102.103", 25565, 24454);

        manager.ensureStartingAsync(r);
        manager.ensureStartingAsync(r);
        assertEquals(HelperStatus.STARTING, manager.status());
        Optional<InetSocketAddress> addr = manager.loopbackFor(r);
        manager.ensureStartingAsync(r);

        assertEquals(Optional.of(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 51234)),
                addr);
        assertArrayEquals(new byte[] {127, 0, 0, 1}, addr.orElseThrow().getAddress().getAddress());
        assertEquals(HelperStatus.READY, manager.status());
        assertEquals(1, factory.calls.size());

        Call c = factory.calls.get(0);
        Path exe = gameDir.resolve("cattail/bin/" + HelperManager.helperVersion() + "/cattail-helper");
        assertEquals(exe, c.exe());
        assertArrayEquals(EXE, Files.readAllBytes(exe));
        assertEquals(List.of("--target", "100.101.102.103:25565", "--voice", "24454", "--hostname", "mc-alice",
                "--log-dir", gameDir.toString(), "--tag", "tag:example"), c.args());
        assertEquals(Map.of("TS_AUTHKEY", KEY + "?ephemeral=true&preauthorized=true"), c.env());
        assertTrue(c.args().stream().noneMatch(a -> a.contains(KEY)), "secret must never be in argv");
        assertTrue(c.readyTimeout().compareTo(Duration.ofSeconds(20)) <= 0);
        assertTrue(c.readyTimeout().compareTo(Duration.ofSeconds(15)) > 0);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void concurrentEnsureStartingStartsOneProcess() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        factory.fakeEnv.put(0, Map.of("FAKE_DELAY_MS", "200"));
        Route r = route("100.101.102.103", 25565, 0);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    manager.ensureStartingAsync(r);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            t.start();
            threads.add(t);
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(10_000);
        }

        assertTrue(manager.loopbackFor(r).isPresent());
        assertEquals(1, factory.calls.size());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void forceRelayConfigSetsEnv() throws Exception {
        forceRelay.set(true);
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));

        assertTrue(manager.loopbackFor(route("100.101.102.103", 25565, 0)).isPresent());

        assertEquals("1", factory.calls.get(0).env().get("CATTAIL_FORCE_RELAY"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void deadHelperIsRestartedOnceThenFailsAsCrashed() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        Route r = route("100.101.102.103", 25565, 0);
        assertTrue(manager.loopbackFor(r).isPresent());

        kill(factory.started.get(0));
        assertTrue(manager.loopbackFor(r).isPresent(), "first death: restart once");
        assertEquals(2, factory.calls.size());
        assertEquals(HelperStatus.READY, manager.status());

        kill(factory.started.get(1));
        assertEquals(Optional.empty(), manager.loopbackFor(r), "second death: give up");
        assertEquals(HelperStatus.FAILED, manager.status());
        assertEquals(Optional.of("helper-crashed"), manager.failureReason());
        assertEquals(2, factory.calls.size());

        manager.ensureStartingAsync(r);
        assertEquals(HelperStatus.FAILED, manager.status(), "helper-crashed is final for the same route");
        assertEquals(Optional.empty(), manager.loopbackFor(r));
        assertEquals(Optional.of("helper-crashed"), manager.failureReason());
        assertEquals(2, factory.calls.size(), "no further restarts in this session");

        assertTrue(manager.loopbackFor(route("100.1.2.3", 25565, 0)).isPresent(),
                "a different route still begins a fresh run");
        assertEquals(3, factory.calls.size());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void routeChangeDuringStartKillsTheStartingHelperFirst() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        factory.fakeEnv.put(0, Map.of("FAKE_DELAY_MS", "10000"));
        Route a = route("100.101.102.103", 25565, 0);
        Route b = route("100.1.2.3", 25566, 0);

        try (AlivePoller poller = new AlivePoller()) {
            manager.ensureStartingAsync(a);
            awaitAliveFakes(1); // first fake is inside FAKE_DELAY_MS
            assertEquals(HelperStatus.STARTING, manager.status());

            long t0 = System.nanoTime();
            assertTrue(manager.loopbackFor(b).isPresent());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 5_000,
                    "the stale start is cancelled, not waited out");

            assertEquals(2, factory.calls.size());
            assertEquals(List.of(0L, 0L), factory.aliveAtStart, "first fake dead before the second started");
            awaitAliveFakes(1);
            assertTrue(poller.max() <= 1, "at most one fake alive at any poll, saw " + poller.max());
        }
        assertEquals(List.of("--target", "100.1.2.3:25566", "--voice", "0", "--hostname", "mc-alice",
                "--log-dir", gameDir.toString(), "--tag", "tag:example"), factory.calls.get(1).args());
        assertEquals(HelperStatus.READY, manager.status());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void shutdownThenRestartDuringStartKeepsOneHelper() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        factory.fakeEnv.put(0, Map.of("FAKE_DELAY_MS", "10000"));
        Route a = route("100.101.102.103", 25565, 0);

        try (AlivePoller poller = new AlivePoller()) {
            manager.ensureStartingAsync(a);
            awaitAliveFakes(1);
            manager.shutdown();
            assertEquals(0, aliveFakes(), "shutdown returns once the starting helper is dead");
            manager.ensureStartingAsync(a);
            assertTrue(manager.loopbackFor(a).isPresent());

            assertEquals(List.of(0L, 0L), factory.aliveAtStart);
            assertTrue(poller.max() <= 1, "at most one fake alive at any poll, saw " + poller.max());
        }
    }

    @Test
    void errorInTheStartThreadFailsAsInternalError() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20), () -> {
            throw new NoClassDefFoundError("net/minecraft/client/Minecraft");
        });

        long t0 = System.nanoTime();
        assertEquals(Optional.empty(), manager.loopbackFor(route("100.101.102.103", 25565, 0)));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 5_000, "no wait for the budget");
        assertEquals(HelperStatus.FAILED, manager.status());
        assertEquals(Optional.of("internal-error"), manager.failureReason());
        assertEquals(0, factory.calls.size());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void readyTimeoutIsRetriedAsAFreshRun() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofMillis(800));
        factory.fakeEnv.put(0, Map.of("FAKE_DELAY_MS", "10000"));
        Route r = route("100.101.102.103", 25565, 0);

        assertEquals(Optional.empty(), manager.loopbackFor(r));
        awaitStatus(manager, HelperStatus.FAILED);
        assertEquals(Optional.of("ready-timeout"), manager.failureReason());

        manager.ensureStartingAsync(r);
        assertEquals(HelperStatus.STARTING, manager.status());
        assertTrue(manager.loopbackFor(r).isPresent(), "fresh run with a fresh budget");
        assertEquals(2, factory.calls.size());
        assertEquals(HelperStatus.READY, manager.status());
    }

    @Test
    void missingHelperIsRetried() throws Exception {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        manager = new HelperManager(gameDir, () -> {
            asked.incrementAndGet();
            return Optional.empty();
        }, () -> deployment, factory, () -> "x", forceRelay::get, Duration.ofSeconds(20), probeRunner, () -> today);
        Route r = route("100.101.102.103", 25565, 0);

        assertEquals(Optional.empty(), manager.loopbackFor(r));
        assertEquals(Optional.empty(), manager.loopbackFor(r));
        assertEquals(2, asked.get(), "helper-missing is not sticky");
        assertEquals(Optional.of("helper-missing"), manager.failureReason());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void helperThatDiesOnItsOwnCountsAsADeath() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        factory.fakeEnv.put(0, Map.of("FAKE_MODE", "die"));
        Route r = route("100.101.102.103", 25565, 0);
        assertTrue(manager.loopbackFor(r).isPresent());

        factory.started.get(0).handle().onExit().get(10, TimeUnit.SECONDS);
        assertTrue(manager.loopbackFor(r).isPresent());
        assertEquals(2, factory.calls.size());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void differentRouteStopsTheHelperAndStartsAFreshRun() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        Route a = route("100.101.102.103", 25565, 0);
        Route b = route("100.1.2.3", 25566, 24454);

        assertTrue(manager.loopbackFor(a).isPresent());
        kill(factory.started.get(0));
        assertTrue(manager.loopbackFor(a).isPresent(), "one death used up on route a");
        HelperProcess second = factory.started.get(1);

        assertTrue(manager.loopbackFor(b).isPresent());
        assertEquals(3, factory.calls.size());
        assertFalse(second.isAlive());
        assertEquals(OptionalInt.of(0), second.exitCode(), "old helper is stopped with QUIT, not killed");
        assertEquals(List.of("--target", "100.1.2.3:25566", "--voice", "24454", "--hostname", "mc-alice",
                "--log-dir", gameDir.toString(), "--tag", "tag:example"), factory.calls.get(2).args());

        kill(factory.started.get(2));
        assertTrue(manager.loopbackFor(b).isPresent(), "death counter was reset by the new run");
        assertEquals(4, factory.calls.size());
        assertEquals(HelperStatus.READY, manager.status());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void voiceOnlyChangeStartsAFreshRun() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));

        assertTrue(manager.loopbackFor(route("100.101.102.103", 25565, 25565)).isPresent());
        HelperProcess first = factory.started.get(0);

        assertTrue(manager.loopbackFor(route("100.101.102.103", 25565, 0)).isPresent());
        assertEquals(2, factory.calls.size());
        assertFalse(first.isAlive());
        List<String> args = factory.calls.get(1).args();
        int voice = args.indexOf("--voice");
        assertTrue(voice >= 0, "second run passes --voice");
        assertEquals(List.of("--voice", "0"), args.subList(voice, voice + 2));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void readyTimeoutFailsWithReason() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofMillis(500));
        factory.fakeEnv.put(0, Map.of("FAKE_DELAY_MS", "10000"));
        Route r = route("100.101.102.103", 25565, 0);

        long t0 = System.nanoTime();
        assertEquals(Optional.empty(), manager.loopbackFor(r));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 5_000);

        awaitStatus(manager, HelperStatus.FAILED);
        assertEquals(Optional.of("ready-timeout"), manager.failureReason());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void helperErrorIsSurfaced() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        factory.fakeEnv.put(0, Map.of("FAKE_MODE", "err"));

        assertEquals(Optional.empty(), manager.loopbackFor(route("100.101.102.103", 25565, 0)));
        assertEquals(HelperStatus.FAILED, manager.status());
        assertEquals(Optional.of("helper-error:auth"), manager.failureReason());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void authFailureAfterExpiryIsKeyExpired() throws Exception {
        deployment = new Deployment.Configured(expiringAuthKey());
        today = LocalDate.of(2026, 10, 21);
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        factory.fakeEnv.put(0, Map.of("FAKE_MODE", "err"));

        assertEquals(Optional.empty(), manager.loopbackFor(route("100.101.102.103", 25565, 0)));
        assertEquals(HelperStatus.FAILED, manager.status());
        assertEquals(Optional.of("key-expired"), manager.failureReason());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void authFailureBeforeExpiryKeepsHelperError() throws Exception {
        deployment = new Deployment.Configured(expiringAuthKey());
        today = LocalDate.of(2026, 10, 19);
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        factory.fakeEnv.put(0, Map.of("FAKE_MODE", "err"));

        assertEquals(Optional.empty(), manager.loopbackFor(route("100.101.102.103", 25565, 0)));
        assertEquals(HelperStatus.FAILED, manager.status());
        assertEquals(Optional.of("helper-error:auth"), manager.failureReason());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void shutdownStopsTheHelper() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        assertTrue(manager.loopbackFor(route("100.101.102.103", 25565, 0)).isPresent());
        HelperProcess p = factory.started.get(0);

        manager.shutdown();
        manager.shutdown();

        assertFalse(p.isAlive());
        assertEquals(OptionalInt.of(0), p.exitCode());
        assertEquals(HelperStatus.STOPPED, manager.status());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void shutdownDuringStartStopsTheLateProcess() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        factory.fakeEnv.put(0, Map.of("FAKE_DELAY_MS", "300"));
        manager.ensureStartingAsync(route("100.101.102.103", 25565, 0));

        manager.shutdown();

        assertEquals(0, aliveFakes(), "the starting helper is cancelled and dead when shutdown returns");
        assertTrue(factory.calls.size() <= 1);
        assertTrue(factory.started.stream().noneMatch(HelperProcess::isAlive));
        assertEquals(HelperStatus.STOPPED, manager.status());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void noProbeTargetsMeansNoProbeRun() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));

        assertTrue(manager.loopbackFor(route("100.101.102.103", 25565, 0)).isPresent());

        assertEquals(List.of("start"), events);
    }

    @Test
    void probeTargetsComeFromSettings() throws Exception {
        launchFakes = false;
        deployment = new Deployment.Configured(settings(KEY, Optional.of("100.101.102.103:25565")));
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));

        manager.loopbackFor(route("100.101.102.103", 25565, 0));

        assertEquals(List.of("probe", "start"), events);
        assertEquals("100.101.102.103:25565", probeCalls.get(0).targets());
        assertEquals(List.of("--hostname", "mc-alice", "--log-dir", gameDir.toString(), "--tag", "tag:example"),
                probeCalls.get(0).baseArgs());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void probeRunsOnceBeforeTheFirstStartWithTheSameEnvAndHostname() throws Exception {
        forceRelay.set(true);
        deployment = new Deployment.Configured(settings(KEY, Optional.of(TARGETS)));
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        Route r = route("100.101.102.103", 25565, 0);

        assertTrue(manager.loopbackFor(r).isPresent(), "the normal start still reaches READY");
        assertEquals(HelperStatus.READY, manager.status());
        assertEquals(List.of("probe", "start"), events);

        ProbeCall p = probeCalls.get(0);
        Path exe = gameDir.resolve("cattail/bin/" + HelperManager.helperVersion() + "/cattail-helper");
        assertEquals(exe, p.exe());
        assertEquals(TARGETS, p.targets());
        assertEquals(List.of("--hostname", "mc-alice", "--log-dir", gameDir.toString(), "--tag", "tag:example"), p.baseArgs());
        assertEquals(Duration.ofSeconds(30), p.timeout());
        assertEquals(factory.calls.get(0).env(), p.env(), "same env (auth key, force relay) as the normal start");
        assertEquals("1", p.env().get("CATTAIL_FORCE_RELAY"));
        assertTrue(p.baseArgs().stream().noneMatch(a -> a.contains(KEY)), "secret must never be in argv");
        assertEquals(List.of("PROBE 100.101.102.103:22 denied", "PROBE 100.1.2.3:22 denied",
                "PROBE 100.101.102.103:25565 allowed"), probeLines);

        kill(factory.started.get(0));
        assertTrue(manager.loopbackFor(r).isPresent(), "restart after a death");
        assertTrue(manager.loopbackFor(route("100.1.2.3", 25565, 0)).isPresent(), "new run");
        assertEquals(List.of("probe", "start", "start", "start"), events, "the probe runs once per manager");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void failingProbeDoesNotStopTheNormalStart() throws Exception {
        probeMode = "err";
        deployment = new Deployment.Configured(settings(KEY, Optional.of(TARGETS)));
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));

        assertTrue(manager.loopbackFor(route("100.101.102.103", 25565, 0)).isPresent());
        assertEquals(List.of("probe", "start"), events);
        assertEquals(HelperStatus.READY, manager.status());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void hangingProbeDoesNotEatTheStartBudget() throws Exception {
        probeMode = "probe-hang";
        probeTimeoutOverride = Duration.ofMillis(1_200);
        deployment = new Deployment.Configured(settings(KEY, Optional.of(TARGETS)));
        manager = newManager(Optional.of(bundle()), Duration.ofMillis(1_000));

        assertTrue(manager.loopbackFor(route("100.101.102.103", 25565, 0)).isPresent(),
                "the start gets a fresh budget after the probe");
        assertEquals(List.of("probe", "start"), events);
        assertEquals(List.of(0L), factory.aliveAtStart, "the timed-out probe is dead before the start");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void shutdownDuringTheProbeKillsIt() throws Exception {
        probeMode = "probe-hang";
        deployment = new Deployment.Configured(settings(KEY, Optional.of(TARGETS)));
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        manager.ensureStartingAsync(route("100.101.102.103", 25565, 0));
        awaitAliveFakes(1);

        long t0 = System.nanoTime();
        manager.shutdown();

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 5_000, "the probe is not waited out");
        assertEquals(0, aliveFakes(), "the probe process is gone when shutdown returns");
        assertEquals(List.of("probe"), events, "no normal start after shutdown");
        assertEquals(HelperStatus.STOPPED, manager.status());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void pingForAnotherRouteWhileServingDoesNotSwitch() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        Route a = route("100.101.102.103", 25565, 0);
        Route b = route("100.1.2.3", 25565, 0);

        assertTrue(manager.loopbackFor(a).isPresent());
        HelperProcess serving = factory.started.get(0);

        assertFalse(manager.ensureStartingIfIdleOrSame(b), "a ping must not take the helper over");
        assertEquals(Optional.empty(), manager.awaitLoopback(b));
        assertEquals(1, factory.calls.size(), "no new helper start");
        assertTrue(serving.isAlive());
        assertEquals(HelperStatus.READY, manager.status());
        assertTrue(manager.awaitLoopback(a).isPresent(), "still serving route a");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void pingForAnotherRouteWhileStartingDoesNotSwitch() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        factory.fakeEnv.put(0, Map.of("FAKE_DELAY_MS", "300"));
        Route a = route("100.101.102.103", 25565, 0);
        Route b = route("100.1.2.3", 25565, 0);

        manager.ensureStartingAsync(a);
        assertFalse(manager.ensureStartingIfIdleOrSame(b));
        assertTrue(manager.loopbackFor(a).isPresent());
        assertEquals(1, factory.calls.size());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void pingForTheSameRouteAttachesToTheRunningHelper() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        Route a = route("100.101.102.103", 25565, 0);

        assertTrue(manager.loopbackFor(a).isPresent());
        assertTrue(manager.ensureStartingIfIdleOrSame(a));
        Optional<InetSocketAddress> addr = manager.awaitLoopback(a);

        assertEquals("127.0.0.1", addr.orElseThrow().getAddress().getHostAddress());
        assertEquals(51234, addr.orElseThrow().getPort());
        assertEquals(1, factory.calls.size());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void pingWhenIdleStartsTheHelper() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        Route a = route("100.101.102.103", 25565, 0);

        assertTrue(manager.ensureStartingIfIdleOrSame(a));
        assertTrue(manager.awaitLoopback(a).isPresent());
        assertEquals(1, factory.calls.size());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void pingAfterARetryableFailureOfAnotherRouteStartsTheHelper() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofMillis(500));
        factory.fakeEnv.put(0, Map.of("FAKE_DELAY_MS", "10000"));
        Route a = route("100.101.102.103", 25565, 0);
        Route b = route("100.1.2.3", 25565, 0);

        assertEquals(Optional.empty(), manager.loopbackFor(a));
        awaitStatus(manager, HelperStatus.FAILED);

        assertTrue(manager.ensureStartingIfIdleOrSame(b), "a failed run is idle");
        assertTrue(manager.awaitLoopback(b).isPresent());
        assertEquals(2, factory.calls.size());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void joinForAnotherRouteWhileServingSwitches() throws Exception {
        manager = newManager(Optional.of(bundle()), Duration.ofSeconds(20));
        Route a = route("100.101.102.103", 25565, 0);
        Route b = route("100.1.2.3", 25565, 0);

        assertTrue(manager.loopbackFor(a).isPresent());
        assertTrue(manager.loopbackFor(b).isPresent());
        assertEquals(2, factory.calls.size());
        assertFalse(factory.started.get(0).isAlive());
    }

    /**
     * Resource {@code name} as the mod jar would hold it, looked up under {@code /bundle-fixture/}
     * of the test resources only: the test classpath also holds the real helpers that
     * scripts/build.sh writes to the main resources, which a plain lookup would find.
     */
    private static URL fixtureResource(String name) {
        return HelperManagerTest.class.getResource("/bundle-fixture" + name);
    }

    @Test
    void loadBundleReadsTheTargetDirectory() throws Exception {
        // Fixture: helper/linux-amd64/cattail-helper (bytes "fixture") and cattail-helper.sha256 with its hash.
        byte[] fixture = "fixture".getBytes(StandardCharsets.US_ASCII);
        HelperManager.HelperBundle b =
                HelperManager.loadBundle(HelperTarget.LINUX_AMD64, HelperManagerTest::fixtureResource).orElseThrow();
        assertEquals("cattail-helper", b.binaryName());
        assertEquals(HelperInstallerTest.sha256(fixture), b.sha256());
        try (InputStream first = b.exe().open(); InputStream second = b.exe().open()) {
            assertArrayEquals(fixture, first.readAllBytes());
            assertArrayEquals(fixture, second.readAllBytes(), "every open is a fresh stream of the binary");
        }
    }

    @Test
    void loadBundleIsEmptyForATargetWithNoResources() {
        assertEquals(Optional.empty(),
                HelperManager.loadBundle(HelperTarget.DARWIN_ARM64, HelperManagerTest::fixtureResource));
    }

    @Test
    void loadBundleWithoutALookupReadsTheModsOwnResources() {
        // The main resources hold the real helpers only once scripts/build.sh has run (as in its
        // test run); either way the production form must agree with HelperManager's own lookup.
        for (HelperTarget t : HelperTarget.values()) {
            String dir = "/helper/" + t.resourceDir() + "/";
            boolean bundled = HelperManager.class.getResource(dir + t.binaryName()) != null
                    && HelperManager.class.getResource(dir + "cattail-helper.sha256") != null;
            Optional<HelperManager.HelperBundle> b = HelperManager.loadBundle(t);
            assertEquals(bundled, b.isPresent(), t.name());
            b.ifPresent(bundle -> assertEquals(t.binaryName(), bundle.binaryName(), t.name()));
        }
    }

    @Test
    void loadBundleIsEmptyWithoutAValidHash() {
        String shaRes = "/helper/linux-amd64/cattail-helper.sha256";
        assertEquals(Optional.empty(), HelperManager.loadBundle(HelperTarget.LINUX_AMD64,
                name -> name.equals(shaRes) ? null : fixtureResource(name)), "no hash file");
        assertEquals(Optional.empty(), HelperManager.loadBundle(HelperTarget.LINUX_AMD64,
                name -> fixtureResource(name.equals(shaRes) ? "/helper/linux-amd64/cattail-helper" : name)),
                "a hash file whose first line is no sha256 (the binary's bytes here)");
    }

    @Test
    void sha256LineIsParsedLikeSha256sumOutput() {
        String sha = "ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        assertEquals(Optional.of(sha.toLowerCase(java.util.Locale.ROOT)),
                HelperManager.parseSha256Line(sha + "  cattail-helper.exe"));
        assertEquals(Optional.of(sha.toLowerCase(java.util.Locale.ROOT)), HelperManager.parseSha256Line(sha));
        assertEquals(Optional.empty(), HelperManager.parseSha256Line("abc123  cattail-helper.exe"));
        assertEquals(Optional.empty(), HelperManager.parseSha256Line("z" + sha.substring(1)));
        assertEquals(Optional.empty(), HelperManager.parseSha256Line(""));
        assertEquals(Optional.empty(), HelperManager.parseSha256Line(null));
    }

    @Test
    void readyLogLineNamesModVersionAndShortHelperSha() {
        String sha = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        assertEquals("abcdef012345", HelperManager.shortSha(sha));
        assertEquals("Cattail helper ready: mod=0.1.0 helper sha=abcdef012345 port=51234 ip=100.1.2.3",
                HelperManager.readyLine("0.1.0", sha, 51234, "100.1.2.3"));
    }
}
