package net.kuching.cattail.helper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One running {@code cattail-helper.exe}. Speaks the helper's stdout protocol: exactly one
 * {@code READY <port> <ipv4>} line first, then only {@code WARN <code> <detail>} /
 * {@code ERR <code> <detail>} lines. The helper exits on {@code QUIT} or stdin EOF.
 *
 * <p>The environment passed to {@link #start} may carry secrets; it is handed to the child
 * process only and never logged. Arguments are logged.
 */
public final class HelperProcess {

    private static final Logger LOGGER = LoggerFactory.getLogger(HelperProcess.class);
    private static final Duration STOP_GRACE = Duration.ofSeconds(5);
    private static final Pattern READY = Pattern.compile(
            "READY ([1-9][0-9]{0,4}) ((?:0|[1-9][0-9]{0,2})\\.(?:0|[1-9][0-9]{0,2})\\."
                    + "(?:0|[1-9][0-9]{0,2})\\.(?:0|[1-9][0-9]{0,2}))");
    private static final Pattern STATUS_LINE = Pattern.compile("(WARN|ERR) (\\S+)(?: (.*))?");

    /** A parsed READY line. */
    record Ready(int port, String ip) {}

    private final Process process;
    private final Ready ready;
    private volatile String lastError;
    private final Object stopLock = new Object();
    private boolean stopped;

    private HelperProcess(Process process, Ready ready) {
        this.process = process;
        this.ready = ready;
    }

    /**
     * Launches the helper (no shell) and waits up to {@code readyTimeout} for its READY line.
     *
     * @throws HelperStartException when READY does not arrive in time, is malformed, or the
     *                              helper reports an error or exits first; the process is destroyed
     * @throws IOException          when the process cannot be launched at all
     */
    public static HelperProcess start(Path exe, Map<String, String> env, List<String> args, Duration readyTimeout)
            throws IOException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(exe.toString());
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        pb.redirectErrorStream(false);
        LOGGER.info("Starting Cattail helper: {}", command);
        Process process = pb.start();
        long pid = process.pid();

        CompletableFuture<Ready> readyFuture = new CompletableFuture<>();
        // HelperProcess does not exist until READY; the stdout reader reports post-READY errors through this.
        CompletableFuture<HelperProcess> self = new CompletableFuture<>();
        startReader(process.getInputStream(), "cattail-helper-stdout-" + pid,
                line -> onStdoutLine(line, readyFuture, self),
                () -> onStdoutEof(process, readyFuture));
        startReader(process.getErrorStream(), "cattail-helper-stderr-" + pid,
                line -> logStderrLine(LOGGER, line), () -> { });

        Ready ready;
        try {
            try {
            long nanos = Math.max(0, readyTimeout.toNanos());
            ready = readyFuture.get(nanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                throw fail(process, "ready-timeout");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                String reason = cause instanceof HelperStartException hse ? hse.reason() : "helper-failed";
                throw fail(process, reason);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw fail(process, "interrupted");
            }
        } catch (HelperStartException e) {
            // Releases a reader that is waiting to record a post-READY ERR; there is no HelperProcess.
            self.completeExceptionally(e);
            readyFuture.completeExceptionally(e); // later lines are logged only
            throw e;
        }
        HelperProcess hp = new HelperProcess(process, ready);
        self.complete(hp);
        LOGGER.info("Cattail helper ready: loopback port {}, tailnet IP {}", ready.port(), ready.ip());
        return hp;
    }

    private static HelperStartException fail(Process process, String reason) {
        destroyTree(process);
        LOGGER.warn("Cattail helper failed to start: {}", reason);
        return new HelperStartException(reason, OptionalLong.of(process.pid()));
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        waitForUninterruptibly(process, STOP_GRACE);
    }

    /**
     * Waits for the process to exit even if this thread is (or gets) interrupted, so a cancelled
     * start really leaves no process behind; the interrupt status is restored afterwards.
     */
    private static boolean waitForUninterruptibly(Process process, Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void onStdoutLine(String rawLine, CompletableFuture<Ready> readyFuture,
            CompletableFuture<HelperProcess> self) {
        String line = stripCr(rawLine);
        if (readyFuture.isCompletedExceptionally()) {
            // start() has failed (or is failing); nothing is waiting for READY or lastError any more.
            LOGGER.debug("helper stdout after a failed start: {}", line);
            return;
        }
        if (!readyFuture.isDone()) {
            Optional<Ready> ready = parseReady(line);
            if (ready.isPresent()) {
                readyFuture.complete(ready.get());
                return;
            }
            Matcher m = STATUS_LINE.matcher(line);
            if (m.matches() && m.group(1).equals("ERR")) {
                LOGGER.error("helper: {}", line);
                readyFuture.completeExceptionally(
                        new HelperStartException("helper-error:" + m.group(2), OptionalLong.empty()));
            } else {
                LOGGER.error("helper sent an invalid first line: {}", line);
                readyFuture.completeExceptionally(new HelperStartException("ready-malformed", OptionalLong.empty()));
            }
            return;
        }
        Matcher m = STATUS_LINE.matcher(line);
        if (!m.matches()) {
            logStdoutLine(LOGGER, line);
            return;
        }
        if (m.group(1).equals("WARN")) {
            LOGGER.warn("helper: {}", line);
        } else {
            LOGGER.error("helper: {}", line);
            String reason = "helper-error:" + m.group(2);
            // READY has completed, so start() is about to (or did) complete self; wait for it briefly.
            try {
                self.get(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS).lastError = reason;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                // start() failed after all; nothing to record on.
            }
        }
    }

    /**
     * The helper never writes stderr in normal operation, so a stderr line is a Go panic or runtime
     * fatal error: logged at WARN so it shows in latest.log.
     */
    static void logStderrLine(Logger log, String rawLine) {
        log.warn("helper stderr: {}", stripCr(rawLine));
    }

    /** A stdout line outside the READY/WARN/ERR protocol: debug chatter only. */
    static void logStdoutLine(Logger log, String line) {
        log.debug("helper stdout: {}", line);
    }

    private static void onStdoutEof(Process process, CompletableFuture<Ready> readyFuture) {
        if (readyFuture.isDone()) {
            return;
        }
        String reason;
        try {
            if (process.waitFor(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                reason = "helper-exited:" + process.exitValue();
            } else {
                reason = "helper-exited:stdout-closed";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reason = "helper-exited:unknown";
        }
        readyFuture.completeExceptionally(new HelperStartException(reason, OptionalLong.empty()));
    }

    private static void startReader(InputStream in, String name, Consumer<String> onLine, Runnable onEof) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (IOException e) {
                LOGGER.debug("{} closed: {}", name, e.toString());
            } finally {
                onEof.run();
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** Parses exactly {@code READY <port 1-65535> <dotted IPv4>}; a trailing CR is tolerated. */
    static Optional<Ready> parseReady(String rawLine) {
        Matcher m = READY.matcher(stripCr(rawLine));
        if (!m.matches()) {
            return Optional.empty();
        }
        int port = Integer.parseInt(m.group(1));
        if (port > 65535) {
            return Optional.empty();
        }
        for (String octet : m.group(2).split("\\.")) {
            if (Integer.parseInt(octet) > 255) {
                return Optional.empty();
            }
        }
        return Optional.of(new Ready(port, m.group(2)));
    }

    /** The helper's TCP listener on 127.0.0.1. */
    public int loopbackPort() {
        return ready.port();
    }

    /** The helper's own tailnet IPv4. */
    public String tailscaleIp() {
        return ready.ip();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** The last {@code ERR} the helper reported after READY, as {@code helper-error:<code>}. */
    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    /** Exit code once the process has exited. */
    public OptionalInt exitCode() {
        return process.isAlive() ? OptionalInt.empty() : OptionalInt.of(process.exitValue());
    }

    ProcessHandle handle() {
        return process.toHandle();
    }

    /**
     * Sends {@code QUIT}, closes stdin, waits 5 s, then kills the process tree. Idempotent; every
     * caller returns only once the process is gone.
     */
    public void stop() {
        synchronized (stopLock) {
            if (stopped) {
                return;
            }
            stopped = true;
            if (process.isAlive()) {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write("QUIT\n".getBytes(StandardCharsets.US_ASCII));
                    stdin.flush();
                } catch (IOException e) {
                    LOGGER.debug("helper stdin already closed: {}", e.toString());
                }
            } else {
                try {
                    process.getOutputStream().close();
                } catch (IOException e) {
                    LOGGER.debug("closing helper stdin: {}", e.toString());
                }
            }
            if (waitForUninterruptibly(process, STOP_GRACE)) {
                LOGGER.info("Cattail helper exited with code {}", process.exitValue());
            } else {
                LOGGER.warn("Cattail helper did not exit within {} s of QUIT; killing it", STOP_GRACE.toSeconds());
                destroyTree(process);
            }
        }
    }
}
