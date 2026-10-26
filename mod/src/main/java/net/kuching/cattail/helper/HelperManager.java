package net.kuching.cattail.helper;

import net.kuching.cattail.Platform;
import net.kuching.cattail.deploy.Deployment;
import net.kuching.cattail.deploy.DeploymentSettings;
import net.kuching.cattail.dns.Route;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the one helper process of this game session.
 *
 * <p>A <em>run</em> serves one {@link Route} (target, port, voice port). Each run gets a 20 s
 * budget from its start for the helper to print READY. If the helper dies, the next
 * {@link #ensureStartingAsync} / {@link #loopbackFor} restarts it once (with a fresh budget);
 * a second death in the same run is final: {@link HelperStatus#FAILED}, reason
 * {@code helper-crashed}, which stays final while that route is asked for. Any other failure
 * ({@code ready-timeout}, {@code helper-error:*}, {@code helper-missing}, ...) is retried as a
 * fresh run by the next {@link #ensureStartingAsync} for the same route. Asking for a different
 * route stops the helper and begins a new run (death counter reset); only an explicit join does
 * that: server-list pings use {@link #ensureStartingIfIdleOrSame}, which never switches routes. A
 * run that is abandoned
 * while still starting is cancelled, and its process is gone before the next one is launched, so
 * at most one helper process exists at any moment.
 */
public final class HelperManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(HelperManager.class);
    static final Duration START_BUDGET = Duration.ofSeconds(20);
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
    private static final int MAX_USER_LEN = 16;
    private static final String CRASHED = "helper-crashed";
    /** How long shutdown() waits for an abandoned start to kill its process. */
    private static final Duration START_CANCEL_WAIT = Duration.ofSeconds(10);
    /** Upper bound for the probe-build isolation test run before the first start. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    /**
     * The helper listens on 127.0.0.1 only. Not {@link InetAddress#getLoopbackAddress()}, which is
     * {@code ::1} when {@code java.net.preferIPv6Addresses} is set. The host name is set to the
     * literal {@code "127.0.0.1"} because Plasmo Voice re-resolves {@code getHostName()} of the game
     * connection's address: without it a reverse lookup gives {@code localhost}, which can resolve
     * to {@code ::1} and miss the helper.
     */
    public static final InetAddress LOOPBACK_V4 = ipv4Loopback();

    /** Config hook: whether to force the DERP relay. The config (Task 9) replaces this supplier. */
    public static volatile BooleanSupplier forceRelaySetting = () -> false;

    private static final Object INSTANCE_LOCK = new Object();
    private static volatile HelperManager instance;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            HelperManager m = instance;
            if (m != null) {
                m.shutdown();
            }
        }, "cattail-helper-shutdown"));
    }

    /** Launches a helper; {@link HelperProcess#start} in production. */
    @FunctionalInterface
    interface ProcessFactory {
        HelperProcess start(Path exe, Map<String, String> env, List<String> args, Duration readyTimeout)
                throws IOException;
    }

    /** Runs a probe build's isolation test; {@link HelperProbe#runOnce} in production. */
    @FunctionalInterface
    interface ProbeRunner {
        Optional<String> run(Path exe, Map<String, String> env, List<String> baseArgs, String targets,
                Duration timeout, Consumer<String> probeLine);
    }

    /**
     * The helper build bundled in the mod jar for this platform: the binary, its sha256 and its
     * file name ({@link HelperTarget#binaryName()}). The key, tag and probe targets are deployment
     * values and come from {@link DeploymentSettings}.
     */
    record HelperBundle(ExeSource exe, String sha256, String binaryName) {

        /** Opens a fresh stream of the bundled binary. */
        @FunctionalInterface
        interface ExeSource {
            InputStream open() throws IOException;
        }
    }

    private final Path gameDir;
    private final Supplier<Optional<HelperBundle>> bundle;
    private final Supplier<Deployment> deployment;
    private final ProcessFactory factory;
    private final Supplier<String> username;
    private final Supplier<Boolean> forceRelay;
    private final Duration budget;
    private final ProbeRunner probeRunner;
    /** Today's date, against which {@code authkey.expires} is checked after an auth failure. */
    private final Supplier<LocalDate> today;
    /** Set once the probe has run (or was skipped); the session-wide instance makes this once per JVM. */
    private final AtomicBoolean probeDone = new AtomicBoolean();

    // All mutable state is guarded by `this`.
    private HelperStatus status = HelperStatus.STOPPED;
    private String failureReason;
    private Route route;
    private HelperProcess process;
    private long deadlineNanos;
    private int deaths;
    /** Bumped on every start attempt and on shutdown; a start thread whose generation is stale discards its result. */
    private long generation;
    private Path installedExe;
    /** The current start thread (null when none was launched yet). Interrupted when its run is abandoned. */
    private Thread startThread;
    /**
     * Completes when the latest start thread has finished, i.e. its helper is either published as
     * {@link #process} or already dead. The next start thread joins it first, so at most one helper
     * process exists at any moment.
     */
    private CompletableFuture<Void> startDone = CompletableFuture.completedFuture(null);

    HelperManager(Path gameDir, Supplier<Optional<HelperBundle>> bundle, ProcessFactory factory,
            Supplier<String> username, Supplier<Boolean> forceRelay) {
        this(gameDir, bundle, factory, username, forceRelay, START_BUDGET);
    }

    HelperManager(Path gameDir, Supplier<Optional<HelperBundle>> bundle, ProcessFactory factory,
            Supplier<String> username, Supplier<Boolean> forceRelay, Duration budget) {
        this(gameDir, bundle, factory, username, forceRelay, budget, HelperProbe::runOnce);
    }

    HelperManager(Path gameDir, Supplier<Optional<HelperBundle>> bundle, ProcessFactory factory,
            Supplier<String> username, Supplier<Boolean> forceRelay, Duration budget, ProbeRunner probeRunner) {
        this(gameDir, bundle, Deployment::bundled, factory, username, forceRelay, budget, probeRunner, LocalDate::now);
    }

    HelperManager(Path gameDir, Supplier<Optional<HelperBundle>> bundle, Supplier<Deployment> deployment,
            ProcessFactory factory, Supplier<String> username, Supplier<Boolean> forceRelay, Duration budget,
            ProbeRunner probeRunner, Supplier<LocalDate> today) {
        this.gameDir = gameDir;
        this.bundle = bundle;
        this.deployment = deployment;
        this.factory = factory;
        this.username = username;
        this.forceRelay = forceRelay;
        this.budget = budget;
        this.probeRunner = probeRunner;
        this.today = today;
    }

    /**
     * The session-wide manager, wired to the mod jar's helper for this platform's
     * {@link HelperTarget}, its deployment file and the running game.
     */
    public static HelperManager get() {
        HelperManager m = instance;
        if (m == null) {
            synchronized (INSTANCE_LOCK) {
                m = instance;
                if (m == null) {
                    m = new HelperManager(Platform.gameDir(),
                            () -> HelperTarget.current().flatMap(HelperManager::loadBundle), HelperProcess::start,
                            () -> Minecraft.getInstance().getUser().getName(),
                            () -> forceRelaySetting.getAsBoolean());
                    instance = m;
                }
            }
        }
        return m;
    }

    /** Starts the helper for {@code r} in the background unless it is already starting or running for it. */
    public void ensureStartingAsync(Route r) {
        Objects.requireNonNull(r, "route");
        synchronized (this) {
            if (!sameEndpoint(route, r)) {
                beginRun(r);
                return;
            }
            switch (status) {
                case STOPPED -> {
                    deadlineNanos = System.nanoTime() + budget.toNanos();
                    launch(null);
                }
                case READY -> {
                    if (!process.isAlive()) {
                        onDeath();
                    }
                }
                case FAILED -> {
                    if (!CRASHED.equals(failureReason)) {
                        beginRun(r); // every failure except a second crash is retryable
                    }
                }
                case STARTING -> {
                    // in flight
                }
            }
        }
    }

    /**
     * Like {@link #ensureStartingAsync} but never takes the helper away from another route: for
     * server-list pings, which must not switch (and cold-start) the one helper on every refresh.
     * Attaches when the manager is idle (no run, stopped, a dead helper, or a retryable failure) or
     * already starting/serving {@code r}'s endpoint; returns false, touching nothing, when it is
     * busy with a different route.
     */
    public boolean ensureStartingIfIdleOrSame(Route r) {
        Objects.requireNonNull(r, "route");
        synchronized (this) {
            if (!sameEndpoint(route, r) && !idle()) {
                return false;
            }
            ensureStartingAsync(r);
            return true;
        }
    }

    /** True when no run is in use: nothing asked yet, stopped, a dead helper, or a retryable failure. */
    private boolean idle() {
        return switch (status) {
            case STOPPED -> true;
            case READY -> !process.isAlive();
            case FAILED -> !CRASHED.equals(failureReason);
            case STARTING -> false;
        } || route == null;
    }

    /**
     * The loopback address to connect to for {@code r}, starting the helper if needed (switching
     * from another route: an explicit join). Blocks until READY or until the run's 20 s budget is
     * used up; empty when the helper is not usable.
     */
    public Optional<InetSocketAddress> loopbackFor(Route r) {
        ensureStartingAsync(r);
        return awaitLoopback(r);
    }

    /**
     * Waits for the current run to serve {@code r} without starting or switching anything (apart
     * from the once-per-run restart of a dead helper for that same route). Empty when another route
     * is (or becomes) current, or the helper is not usable within the run's budget.
     */
    public Optional<InetSocketAddress> awaitLoopback(Route r) {
        Objects.requireNonNull(r, "route");
        synchronized (this) {
            while (true) {
                if (!sameEndpoint(route, r)) {
                    return Optional.empty(); // another route took over meanwhile
                }
                switch (status) {
                    case READY -> {
                        if (process.isAlive()) {
                            return Optional.of(new InetSocketAddress(LOOPBACK_V4,
                                    process.loopbackPort()));
                        }
                        onDeath();
                    }
                    case FAILED, STOPPED -> {
                        return Optional.empty();
                    }
                    case STARTING -> {
                        long waitNanos = deadlineNanos - System.nanoTime();
                        if (waitNanos <= 0) {
                            return Optional.empty();
                        }
                        try {
                            TimeUnit.NANOSECONDS.timedWait(this, waitNanos);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return Optional.empty();
                        }
                    }
                }
            }
        }
    }

    /** Current status. A helper that died after READY is reported as STOPPED until the next start. */
    public synchronized HelperStatus status() {
        if (status == HelperStatus.READY && !process.isAlive()) {
            return HelperStatus.STOPPED;
        }
        return status;
    }

    /** True when {@link #status()} is READY and the current run serves {@code r}'s endpoint. Never blocks for long. */
    public synchronized boolean isReadyFor(Route r) {
        return r != null && sameEndpoint(route, r) && status() == HelperStatus.READY;
    }

    /**
     * Why the helper is {@link HelperStatus#FAILED}, e.g. {@code not-configured} (generic jar),
     * {@code deployment-invalid:<key>}, {@code key-expired}, {@code helper-missing}, {@code ready-timeout}.
     */
    public synchronized Optional<String> failureReason() {
        return status == HelperStatus.FAILED ? Optional.ofNullable(failureReason) : Optional.empty();
    }

    /**
     * Stops the helper (QUIT, then kill after 5 s), cancels a start in flight and forgets the run.
     * Idempotent; returns once no helper process of this manager is left (bounded wait).
     */
    public void shutdown() {
        HelperProcess toStop;
        CompletableFuture<Void> inFlight;
        synchronized (this) {
            generation++;
            cancelStartThread();
            inFlight = startDone;
            toStop = process;
            process = null;
            route = null;
            status = HelperStatus.STOPPED;
            failureReason = null;
            deaths = 0;
            notifyAll();
        }
        if (toStop != null) {
            toStop.stop();
        }
        try {
            inFlight.get(START_CANCEL_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn("Cattail helper start did not finish within {} s of shutdown", START_CANCEL_WAIT.toSeconds());
        }
    }

    // ---- internals (call with the lock held) ----

    private void beginRun(Route r) {
        cancelStartThread();
        HelperProcess old = process;
        process = null;
        route = r;
        deaths = 0;
        failureReason = null;
        deadlineNanos = System.nanoTime() + budget.toNanos();
        LOGGER.info("Cattail helper run for {} ({}:{}, voice {})", r.host(), r.target().getHostAddress(),
                r.port(), r.voicePort());
        launch(old);
        notifyAll();
    }

    private void onDeath() {
        HelperProcess dead = process;
        process = null;
        deaths++;
        LOGGER.warn("Cattail helper died (exit {}, {}), death {} of this run", dead.exitCode(),
                dead.lastError().orElse("no error reported"), deaths);
        if (deaths >= 2) {
            fail(generation, CRASHED);
            return;
        }
        deadlineNanos = System.nanoTime() + budget.toNanos();
        launch(null);
    }

    private void launch(HelperProcess toStopFirst) {
        status = HelperStatus.STARTING;
        failureReason = null;
        long gen = ++generation;
        Route r = route;
        long deadline = deadlineNanos;
        CompletableFuture<Void> previous = startDone;
        CompletableFuture<Void> done = new CompletableFuture<>();
        startDone = done;
        Thread t = new Thread(() -> {
            try {
                previous.join(); // the abandoned start (if any) has killed or handed over its process
                if (toStopFirst != null) {
                    toStopFirst.stop();
                }
                runStart(gen, r, deadline);
            } catch (Throwable e) { // incl. Errors such as NoClassDefFoundError: never leave STARTING behind
                LOGGER.error("Cattail helper start crashed", e);
                fail(gen, "internal-error");
            } finally {
                done.complete(null);
            }
        }, "cattail-helper-start");
        t.setDaemon(true);
        startThread = t;
        t.start();
    }

    /** Abandons the start in flight: interrupting it makes HelperProcess.start kill its child. */
    private void cancelStartThread() {
        if (status == HelperStatus.STARTING && startThread != null) {
            startThread.interrupt();
        }
    }

    private synchronized void fail(long gen, String reason) {
        if (gen != generation) {
            return;
        }
        status = HelperStatus.FAILED;
        failureReason = reason;
        LOGGER.warn("Cattail helper unavailable: {}", reason);
        notifyAll();
    }

    // ---- start thread ----

    private void runStart(long gen, Route r, long deadline) {
        synchronized (this) {
            if (gen != generation) {
                return; // abandoned before it began
            }
        }
        DeploymentSettings settings;
        switch (deployment.get()) {
            case Deployment.Generic() -> {
                fail(gen, HelperStartException.NOT_CONFIGURED);
                return;
            }
            case Deployment.Invalid(String key) -> {
                fail(gen, HelperStartException.DEPLOYMENT_INVALID + key);
                return;
            }
            case Deployment.Configured(DeploymentSettings s) -> settings = s;
        }
        HelperProcess started = null;
        String helperSha;
        try {
            Optional<HelperBundle> b = bundle.get();
            if (b.isEmpty()) {
                fail(gen, "helper-missing");
                return;
            }
            helperSha = b.get().sha256();
            Path exe = install(b.get());
            Map<String, String> env = new HashMap<>();
            env.put("TS_AUTHKEY", settings.authKeyForHelper());
            if (Boolean.TRUE.equals(forceRelay.get())) {
                env.put("CATTAIL_FORCE_RELAY", "1");
            }
            String hostname = hostnameFor(username.get());
            String tag = settings.tag();
            Optional<String> targets = settings.probeTargets();
            if (targets.isPresent() && !probeDone.get()) {
                synchronized (this) {
                    if (gen != generation) {
                        return; // abandoned: leave the probe to the next start
                    }
                    // Waiters in loopbackFor must not give up while the probe runs.
                    deadlineNanos = System.nanoTime() + PROBE_TIMEOUT.toNanos() + budget.toNanos();
                }
                // Start threads run one at a time (each joins the previous), so get/set is race-free.
                probeDone.set(true);
                runProbe(exe, env, hostname, tag, targets.get());
                synchronized (this) {
                    if (gen != generation) {
                        return; // abandoned (shutdown, other route) while probing
                    }
                    // The probe ran on this run's clock; the start gets a fresh budget.
                    deadline = System.nanoTime() + budget.toNanos();
                    deadlineNanos = deadline;
                    notifyAll();
                }
            }
            List<String> args = new ArrayList<>(List.of(
                    "--target", r.target().getHostAddress() + ":" + r.port(),
                    "--voice", Integer.toString(r.voicePort()),
                    "--hostname", hostname,
                    "--log-dir", gameDir.toString(),
                    "--tag", tag));
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                fail(gen, "ready-timeout");
                return;
            }
            started = factory.start(exe, env, args, Duration.ofNanos(remaining));
        } catch (HelperStartException e) {
            fail(gen, startReason(e.reason(), settings, today.get()));
            return;
        } catch (IOException e) {
            LOGGER.error("Cattail helper could not be launched", e);
            fail(gen, "launch-failed");
            return;
        }
        boolean stale;
        synchronized (this) {
            stale = gen != generation;
            if (!stale) {
                process = started;
                status = HelperStatus.READY;
                notifyAll();
            }
        }
        if (stale) {
            LOGGER.info("Cattail helper became ready after it was no longer wanted; stopping it");
            started.stop();
        } else {
            LOGGER.info(readyLine(helperVersion(), helperSha, started.loopbackPort(), started.tailscaleIp()));
        }
    }

    /** The probe build's isolation test; any failure is logged and ignored, the normal start follows. */
    private void runProbe(Path exe, Map<String, String> env, String hostname, String tag, String targets) {
        Optional<String> failure;
        try {
            failure = probeRunner.run(exe, env,
                    List.of("--hostname", hostname, "--log-dir", gameDir.toString(), "--tag", tag),
                    targets, PROBE_TIMEOUT, line -> LOGGER.info("probe {}", line.substring("PROBE ".length())));
        } catch (RuntimeException e) {
            failure = Optional.of(e.toString());
        }
        failure.ifPresent(reason -> LOGGER.warn("probe run failed: {}", reason));
    }

    private Path install(HelperBundle b) throws IOException {
        synchronized (this) {
            if (installedExe != null) {
                return installedExe;
            }
        }
        Path exe;
        try (InputStream in = b.exe().open()) {
            exe = HelperInstaller.install(gameDir, helperVersion(), b.binaryName(), in, b.sha256());
        } catch (IOException e) {
            LOGGER.error("Installing the Cattail helper failed", e);
            throw new HelperStartException("install-failed", OptionalLong.empty());
        }
        synchronized (this) {
            installedExe = exe;
        }
        return exe;
    }

    /**
     * The failure reason for a start that failed with {@code helperReason}: {@code key-expired} when
     * the helper reported an auth failure ({@code helper-error:auth}) and {@code authkey.expires} has
     * passed, else {@code helperReason} unchanged. The date is informational: the helper always tries
     * to log in, so a key that was extended in the admin console keeps working.
     */
    static String startReason(String helperReason, DeploymentSettings s, LocalDate today) {
        return "helper-error:auth".equals(helperReason) && s.expired(today)
                ? HelperStartException.KEY_EXPIRED
                : helperReason;
    }

    private static boolean sameEndpoint(Route a, Route b) {
        return a != null && b != null && a.target().equals(b.target()) && a.port() == b.port()
                && a.voicePort() == b.voicePort();
    }

    // ---- static helpers ----

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress("127.0.0.1", new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new AssertionError("a 4-byte address is always valid", e);
        }
    }

    /**
     * Tailnet hostname for a Minecraft username; mirrors the helper's {@code node.Hostname}:
     * lower-case, every run of characters outside {@code [a-z0-9]} becomes one '-', trimmed of '-',
     * cut to 16 characters (trailing '-' trimmed again), {@code mc-player} when nothing is left.
     */
    static String hostnameFor(String username) {
        StringBuilder b = new StringBuilder();
        boolean pendingDash = false;
        String lower = username == null ? "" : username.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (pendingDash && b.length() > 0) {
                    b.append('-');
                }
                pendingDash = false;
                b.append(c);
            } else {
                pendingDash = true;
            }
        }
        String user = b.toString();
        if (user.length() > MAX_USER_LEN) {
            user = user.substring(0, MAX_USER_LEN);
            while (user.endsWith("-")) {
                user = user.substring(0, user.length() - 1);
            }
        }
        return "mc-" + (user.isEmpty() ? "player" : user);
    }

    /** The mod version from the jar manifest's {@code Implementation-Version}, or {@code dev}. */
    public static String helperVersion() {
        String v = HelperManager.class.getPackage().getImplementationVersion();
        if (v != null && SAFE_VERSION.matcher(v).matches() && !v.contains("..")) {
            return v;
        }
        Module module = HelperManager.class.getModule();
        if (module.isNamed()) { // in the game: read this mod jar's own manifest
            try (InputStream in = module.getResourceAsStream("META-INF/MANIFEST.MF")) {
                if (in != null) {
                    return versionFrom(new Manifest(in));
                }
            } catch (IOException e) {
                LOGGER.debug("reading the mod manifest failed: {}", e.toString());
            }
        }
        return "dev";
    }

    static String versionFrom(Manifest manifest) {
        String v = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        if (v == null || !SAFE_VERSION.matcher(v).matches() || v.contains("..")) {
            return "dev";
        }
        return v;
    }

    /**
     * Loads {@code target}'s helper from {@code /helper/<resourceDir>/} in the mod jar: its binary
     * and the hash in {@code cattail-helper.sha256} beside it. Empty, with the resource named in
     * the log, when either is missing or the hash is malformed. Only the hash is read here; the
     * binary is streamed by the installer, from a fresh stream per {@link HelperBundle.ExeSource#open}.
     */
    static Optional<HelperBundle> loadBundle(HelperTarget target) {
        return loadBundle(target, HelperManager.class::getResource);
    }

    /**
     * {@link #loadBundle(HelperTarget)} with {@code resources} mapping an absolute resource name to
     * its URL, or to null when there is none; tests pass one that sees only their fixture, since the
     * test classpath also holds the real helpers that scripts/build.sh writes.
     */
    static Optional<HelperBundle> loadBundle(HelperTarget target, Function<String, URL> resources) {
        String dir = "/helper/" + target.resourceDir() + "/";
        String exeRes = dir + target.binaryName();
        String shaRes = dir + "cattail-helper.sha256";
        URL exe = resources.apply(exeRes);
        if (exe == null) {
            LOGGER.error("Cattail helper resource {} is missing from the mod jar", exeRes);
            return Optional.empty();
        }
        URL shaUrl = resources.apply(shaRes);
        Optional<String> shaLine = shaUrl == null ? Optional.empty() : readFirstLine(shaRes, shaUrl);
        if (shaLine.isEmpty()) {
            LOGGER.error("Cattail helper hash resource {} is missing from the mod jar, empty or unreadable", shaRes);
            return Optional.empty();
        }
        Optional<String> sha = parseSha256Line(shaLine.get());
        if (sha.isEmpty()) {
            LOGGER.error("Cattail helper hash resource {} is malformed", shaRes);
            return Optional.empty();
        }
        return Optional.of(new HelperBundle(exe::openStream, sha.get(), target.binaryName()));
    }

    /** The lower-case hash from a {@code sha256sum} line ({@code <64 hex>  <file>}), else empty. */
    static Optional<String> parseSha256Line(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String sha = line.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return sha.matches("[0-9a-f]{64}") ? Optional.of(sha) : Optional.empty();
    }

    /** First 12 hex characters of a sha256: enough to tell helper builds apart in a log. */
    static String shortSha(String sha256) {
        return sha256.substring(0, Math.min(12, sha256.length()));
    }

    /** The INFO line logged once the helper is READY: mod version + helper build + endpoint. */
    static String readyLine(String modVersion, String helperSha256, int port, String ip) {
        return "Cattail helper ready: mod=" + modVersion + " helper sha=" + shortSha(helperSha256)
                + " port=" + port + " ip=" + ip;
    }

    /** The trimmed first line of resource {@code name} at {@code url}; empty when blank or unreadable (logged). */
    private static Optional<String> readFirstLine(String name, URL url) {
        try (InputStream in = url.openStream();
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = r.readLine();
            return line == null || line.isBlank() ? Optional.empty() : Optional.of(line.trim());
        } catch (IOException e) {
            LOGGER.error("reading {} failed: {}", name, e.toString());
            return Optional.empty();
        }
    }
}
