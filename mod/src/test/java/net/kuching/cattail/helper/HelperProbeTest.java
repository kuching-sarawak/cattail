package net.kuching.cattail.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class HelperProbeTest {

    private static final String TARGETS = "100.101.102.103:22,100.1.2.3:22,100.101.102.103:25565";

    @TempDir
    Path dir;

    private Path script;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void copyScript() throws Exception {
        script = FakeHelperScript.copyTo(dir);
    }

    /** Live processes started from {@code path} by this JVM. */
    private static long alive(Path path) {
        String p = path.toString();
        return ProcessHandle.current().descendants()
                .filter(ProcessHandle::isAlive)
                .filter(h -> h.info().commandLine().map(c -> c.contains(p)).orElse(false))
                .count();
    }

    @Test
    void collectsTheProbeLinesAndSucceedsOnExitZero() {
        Optional<String> failure = HelperProbe.runOnce(script, Map.of("FAKE_MODE", "probe"),
                List.of("--hostname", "mc-x", "--log-dir", dir.toString()), TARGETS, Duration.ofSeconds(10),
                lines::add);

        assertEquals(Optional.empty(), failure);
        assertEquals(List.of("PROBE 100.101.102.103:22 denied", "PROBE 100.1.2.3:22 denied",
                "PROBE 100.101.102.103:25565 allowed"), lines);
    }

    @Test
    void probeFlagAndBaseArgsReachTheProcess() throws Exception {
        Path s = dir.resolve("args.sh");
        Files.writeString(s, "#!/bin/sh\n[ \"$1\" = --probe ] && [ \"$2\" = '" + TARGETS
                + "' ] && [ \"$3\" = --hostname ] && [ \"$4\" = mc-x ] && [ \"$FOO\" = bar ] || exit 9\n"
                + "echo 'PROBE 1.2.3.4:5 allowed'\n");
        assertTrue(s.toFile().setExecutable(true));

        Optional<String> failure = HelperProbe.runOnce(s, Map.of("FOO", "bar"), List.of("--hostname", "mc-x"),
                TARGETS, Duration.ofSeconds(10), lines::add);

        assertEquals(Optional.empty(), failure);
        assertEquals(List.of("PROBE 1.2.3.4:5 allowed"), lines);
    }

    @Test
    void neverExitingProbeIsDestroyedAtTheTimeout() {
        long t0 = System.nanoTime();
        Optional<String> failure = HelperProbe.runOnce(script, Map.of("FAKE_MODE", "probe-hang"), List.of(),
                TARGETS, Duration.ofMillis(700), lines::add);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertEquals(Optional.of("timeout after 0.7 s"), failure);
        assertTrue(ms < 5_000, "gave up at the timeout, took " + ms + " ms");
        assertEquals(0, alive(script), "the probe process must be gone");
        assertEquals(List.of("PROBE 100.101.102.103:22 denied"), lines, "lines before the timeout are kept");
    }

    @Test
    void nonZeroExitIsAFailure() {
        Optional<String> failure = HelperProbe.runOnce(script, Map.of("FAKE_MODE", "err"), List.of(), TARGETS,
                Duration.ofSeconds(10), lines::add);

        assertEquals(Optional.of("exit code 3"), failure);
        assertEquals(List.of(), lines);
    }

    @Test
    void unlaunchableExeIsAFailure() {
        Optional<String> failure = HelperProbe.runOnce(dir.resolve("missing.exe"), Map.of(), List.of(), TARGETS,
                Duration.ofSeconds(10), lines::add);

        assertTrue(failure.isPresent() && failure.get().startsWith("launch failed"), failure.toString());
    }

    @Test
    void interruptDestroysTheProbeAndKeepsTheFlag() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Optional<String>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean flag = new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(() -> {
            result.set(HelperProbe.runOnce(script, Map.of("FAKE_MODE", "probe-hang"), List.of(), TARGETS,
                    Duration.ofSeconds(30), lines::add));
            flag.set(Thread.currentThread().isInterrupted());
        });
        t.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (alive(script) == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        t.interrupt();
        t.join(10_000);

        assertEquals(Optional.of("interrupted"), result.get());
        assertTrue(flag.get(), "interrupt status restored");
        assertEquals(0, alive(script));
    }
}
