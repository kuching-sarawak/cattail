package net.kuching.cattail.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import net.kuching.cattail.RecordingLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class HelperProcessTest {

    @TempDir
    Path dir;

    private Path script;

    @BeforeEach
    void copyScript() throws Exception {
        script = FakeHelperScript.copyTo(dir);
    }

    @Test
    void readyLineIsParsedAndStopQuitsCleanly() throws Exception {
        HelperProcess p = HelperProcess.start(script, Map.of(), List.of("--target", "100.1.2.3:25565"),
                Duration.ofSeconds(10));

        assertEquals(51234, p.loopbackPort());
        assertEquals("100.1.2.3", p.tailscaleIp());
        assertTrue(p.isAlive());

        p.stop();

        assertFalse(p.isAlive());
        assertEquals(OptionalInt.of(0), p.exitCode(), "QUIT must end the helper with exit 0");
        assertEquals(Optional.empty(), p.lastError());
        p.stop(); // idempotent
    }

    @Test
    void readyAfterDelayWithinTimeout() throws Exception {
        HelperProcess p = HelperProcess.start(script, Map.of("FAKE_DELAY_MS", "300"), List.of(),
                Duration.ofSeconds(10));
        try {
            assertEquals(51234, p.loopbackPort());
        } finally {
            p.stop();
        }
    }

    @Test
    void readyTimeoutKillsTheProcess() {
        long t0 = System.nanoTime();
        HelperStartException e = assertThrows(HelperStartException.class,
                () -> HelperProcess.start(script, Map.of("FAKE_DELAY_MS", "10000"), List.of(),
                        Duration.ofMillis(500)));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertEquals("ready-timeout", e.reason());
        assertTrue(e.getMessage().contains("ready-timeout"), e.getMessage());
        assertTrue(elapsedMs < 5_000, "start must give up at the timeout, took " + elapsedMs + " ms");
        assertFalse(e.pid().isPresent() && ProcessHandle.of(e.pid().getAsLong()).map(ProcessHandle::isAlive)
                .orElse(false), "the timed-out helper must be destroyed");
    }

    @Test
    void errBeforeReadyIsHelperError() {
        HelperStartException e = assertThrows(HelperStartException.class,
                () -> HelperProcess.start(script, Map.of("FAKE_MODE", "err"), List.of(), Duration.ofSeconds(10)));
        assertEquals("helper-error:auth", e.reason());
    }

    @Test
    void malformedReadyIsRejected() {
        HelperStartException e = assertThrows(HelperStartException.class,
                () -> HelperProcess.start(script, Map.of("FAKE_MODE", "bad"), List.of(), Duration.ofSeconds(10)));
        assertEquals("ready-malformed", e.reason());
        assertTrue(e.pid().isPresent());
        assertFalse(ProcessHandle.of(e.pid().getAsLong()).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void exitBeforeReadyReportsExitCode() throws Exception {
        Path exits = dir.resolve("exits.sh");
        java.nio.file.Files.writeString(exits, "#!/bin/sh\nexit 4\n");
        assertTrue(exits.toFile().setExecutable(true));

        HelperStartException e = assertThrows(HelperStartException.class,
                () -> HelperProcess.start(exits, Map.of(), List.of(), Duration.ofSeconds(10)));
        assertEquals("helper-exited:4", e.reason());
    }

    @Test
    void envAndArgsReachTheProcess() throws Exception {
        Path echo = dir.resolve("echo.sh");
        java.nio.file.Files.writeString(echo,
                "#!/bin/sh\n[ \"$FOO\" = bar ] && [ \"$1\" = --x ] && [ \"$2\" = 'a b' ] || exit 7\n"
                        + "echo 'READY 1 100.101.102.103'\ncat >/dev/null\n");
        assertTrue(echo.toFile().setExecutable(true));

        HelperProcess p = HelperProcess.start(echo, Map.of("FOO", "bar"), List.of("--x", "a b"),
                Duration.ofSeconds(10));
        assertEquals(1, p.loopbackPort());
        assertEquals("100.101.102.103", p.tailscaleIp());
        p.stop();
        assertEquals(OptionalInt.of(0), p.exitCode(), "stdin EOF must end it too");
    }

    @Test
    void deathAfterReadyIsVisible() throws Exception {
        HelperProcess p = HelperProcess.start(script, Map.of("FAKE_MODE", "die"), List.of(),
                Duration.ofSeconds(10));
        p.handle().onExit().get(10, TimeUnit.SECONDS);

        assertFalse(p.isAlive());
        assertEquals(OptionalInt.of(1), p.exitCode());
        p.stop();
    }

    @Test
    void errAfterReadyIsRecordedAsLastError() throws Exception {
        Path s = dir.resolve("relayfail.sh");
        java.nio.file.Files.writeString(s,
                "#!/bin/sh\necho 'READY 2 100.101.102.103'\necho 'ERR relay broken pipe'\nexit 1\n");
        assertTrue(s.toFile().setExecutable(true));

        HelperProcess p = HelperProcess.start(s, Map.of(), List.of(), Duration.ofSeconds(10));
        p.handle().onExit().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (p.lastError().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(Optional.of("helper-error:relay"), p.lastError());
    }

    @Test
    void readyParserIsStrict() {
        assertEquals(new HelperProcess.Ready(51234, "100.1.2.3"),
                HelperProcess.parseReady("READY 51234 100.1.2.3").orElseThrow());
        assertEquals(new HelperProcess.Ready(1, "100.1.2.3"),
                HelperProcess.parseReady("READY 1 100.1.2.3\r").orElseThrow());
        for (String bad : List.of("READY", "READY 51234", "READY 0 100.1.2.3", "READY 65536 100.1.2.3",
                "READY -1 100.1.2.3", "READY +5 100.1.2.3", "READY 5 100.1.2", "READY 5 10.1.2.256",
                "READY 5 ::1", "READY 5 100.1.2.3 x", "READY  5 100.1.2.3", "ready 5 100.1.2.3",
                "READY 5 10.01.2.3", "READY 99999999999 100.1.2.3")) {
            assertTrue(HelperProcess.parseReady(bad).isEmpty(), bad);
        }
    }

    @Test
    void secondErrBeforeReadyDoesNotBlockTheReader() throws Exception {
        Path s = dir.resolve("twoerrs.sh");
        java.nio.file.Files.writeString(s,
                "#!/bin/sh\necho 'ERR auth bad key'\necho 'ERR relay second'\nexit 3\n");
        assertTrue(s.toFile().setExecutable(true));

        long t0 = System.nanoTime();
        HelperStartException e = assertThrows(HelperStartException.class,
                () -> HelperProcess.start(s, Map.of(), List.of(), Duration.ofSeconds(10)));
        assertEquals("helper-error:auth", e.reason(), "the first ERR is the reason");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3_000, "start fails promptly");

        String reader = "cattail-helper-stdout-" + e.pid().getAsLong();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); // the old bug held it for 5 s
        while (readerAlive(reader) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(readerAlive(reader), "stdout reader thread must end on EOF");
    }

    private static boolean readerAlive(String name) {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals(name) && t.isAlive());
    }

    @Test
    void interruptedStartKillsTheChildBeforeReturning() throws Exception {
        java.util.concurrent.atomic.AtomicReference<HelperStartException> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                HelperProcess.start(script, Map.of("FAKE_DELAY_MS", "10000"), List.of(), Duration.ofSeconds(20));
            } catch (HelperStartException e) {
                failure.set(e);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
        t.start();
        Thread.sleep(300);
        t.interrupt();
        t.join(10_000);

        assertFalse(t.isAlive());
        assertEquals("interrupted", failure.get().reason());
        assertFalse(ProcessHandle.of(failure.get().pid().getAsLong()).map(ProcessHandle::isAlive).orElse(false),
                "the child is dead by the time start() returns");
    }

    @Test
    void stderrLinesAreWarnings() {
        List<String> logged = new java.util.ArrayList<>();
        HelperProcess.logStderrLine(RecordingLogger.into(logged), "panic: runtime error\r");
        assertEquals(List.of("warn|helper stderr: {}|panic: runtime error"), logged);
    }

    @Test
    void nonProtocolStdoutLinesStayDebug() {
        List<String> logged = new java.util.ArrayList<>();
        HelperProcess.logStdoutLine(RecordingLogger.into(logged), "some chatter");
        assertEquals(List.of("debug|helper stdout: {}|some chatter"), logged);
    }
}
