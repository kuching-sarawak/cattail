package net.kuching.cattail.helper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One isolation-test run of a probe build of the helper: {@code <exe> --probe <targets> <baseArgs>}.
 * The helper joins the tailnet, test-dials each target, prints {@code PROBE <addr> allowed|denied}
 * per target and exits 0. It prints no READY line and ignores stdin, so it cannot go through
 * {@link HelperProcess#start}.
 *
 * <p>The environment may carry secrets; it is handed to the child only and never logged.
 */
final class HelperProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(HelperProbe.class);
    private static final String PREFIX = "PROBE ";
    private static final Duration KILL_WAIT = Duration.ofSeconds(5);

    private HelperProbe() {
    }

    /**
     * Runs the probe and waits for it to exit, at most {@code timeout} (then the process tree is
     * destroyed). Every stdout line starting with {@code PROBE } goes to {@code probeLine}; other
     * stdout and all stderr lines are logged at DEBUG. Never throws for a failed probe.
     *
     * @return empty when the probe exited 0, otherwise why it failed ({@code exit code N},
     *         {@code timeout after S s}, {@code interrupted}, {@code launch failed: ...}); an
     *         interrupt is passed on (the flag stays set)
     */
    static Optional<String> runOnce(Path exe, Map<String, String> env, List<String> baseArgs, String targets,
            Duration timeout, Consumer<String> probeLine) {
        List<String> command = new ArrayList<>(baseArgs.size() + 3);
        command.add(exe.toString());
        command.add("--probe");
        command.add(targets);
        command.addAll(baseArgs);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        LOGGER.info("Starting Cattail helper probe run: {}", command);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return Optional.of("launch failed: " + e.getMessage());
        }
        try {
            process.getOutputStream().close(); // the probe ignores stdin
        } catch (IOException e) {
            LOGGER.debug("closing probe stdin: {}", e.toString());
        }
        Thread out = reader(process.getInputStream(), "cattail-helper-probe-stdout-" + process.pid(), line -> {
            if (line.startsWith(PREFIX)) {
                probeLine.accept(line);
            } else {
                LOGGER.debug("helper probe stdout: {}", line);
            }
        });
        reader(process.getErrorStream(), "cattail-helper-probe-stderr-" + process.pid(),
                line -> LOGGER.debug("helper probe stderr: {}", line));
        try {
            if (!process.waitFor(Math.max(0, timeout.toNanos()), TimeUnit.NANOSECONDS)) {
                destroy(process);
                return Optional.of(String.format(Locale.ROOT, "timeout after %s s",
                        trimSeconds(timeout)));
            }
            out.join(KILL_WAIT.toMillis()); // let the reader hand over the last lines
        } catch (InterruptedException e) {
            destroy(process);
            Thread.currentThread().interrupt();
            return Optional.of("interrupted");
        }
        int code = process.exitValue();
        return code == 0 ? Optional.empty() : Optional.of("exit code " + code);
    }

    private static String trimSeconds(Duration d) {
        return java.math.BigDecimal.valueOf(d.toMillis(), 3).stripTrailingZeros().toPlainString();
    }

    /** Kills the process tree and waits (uninterruptibly, bounded) until it is gone. */
    private static void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        long deadline = System.nanoTime() + KILL_WAIT.toNanos();
        boolean interrupted = false;
        while (true) {
            try {
                process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread reader(InputStream in, String name, Consumer<String> onLine) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    onLine.accept(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
                }
            } catch (IOException e) {
                LOGGER.debug("{} closed: {}", name, e.toString());
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
