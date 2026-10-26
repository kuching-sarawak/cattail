package net.kuching.cattail.specs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SpecsDispatchTest {

    private static final SysInfo.Hardware PC = new SysInfo.Hardware("cpu", 4, 8, 1L << 33, List.of());
    private final ExecutorService off = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "specs-test-off");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void stop() {
        off.shutdownNow();
    }

    private static SysInfo build(SysInfo.Hardware hw) {
        return SysInfo.collect(hw, "gl", new Properties(), 1L << 30, 8, Optional.empty(), 1, "0.2.0");
    }

    @Test
    void readAsyncCompletesWithTheSummary() throws Exception {
        assertSame(PC, SpecsDispatch.readAsync(() -> PC, Duration.ofSeconds(5), LoggerFactory.getLogger("t"))
                .get(5, TimeUnit.SECONDS));
    }

    @Test
    void readAsyncCompletesUnknownWhenTheReadThrows() throws Exception {
        CompletableFuture<SysInfo.Hardware> f = SpecsDispatch.readAsync(() -> {
            throw new UnsatisfiedLinkError("no JNA");
        }, Duration.ofSeconds(5), LoggerFactory.getLogger("t"));
        assertSame(SpecsDispatch.UNKNOWN, f.get(5, TimeUnit.SECONDS));
        assertFalse(f.isCompletedExceptionally());
    }

    @Test
    void readAsyncCompletesUnknownWhenTheReadOutlivesTheTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<SysInfo.Hardware> f = SpecsDispatch.readAsync(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return PC;
        }, Duration.ofMillis(100), LoggerFactory.getLogger("t"));
        try {
            assertSame(SpecsDispatch.UNKNOWN, f.get(5, TimeUnit.SECONDS), "a wedged read must not block the login");
        } finally {
            release.countDown();
        }
        assertSame(SpecsDispatch.UNKNOWN, f.get(), "the late result does not replace the timeout value");
    }

    @Test
    void sendRunsOnTheExecutorNotTheCallerWhenTheSummaryIsAlreadyDone() throws Exception {
        AtomicReference<String> sentOn = new AtomicReference<>();
        AtomicReference<SysInfo> sent = new AtomicReference<>();
        SpecsDispatch.sendWhenReady(CompletableFuture.completedFuture(PC), off, SpecsDispatchTest::build, info -> {
            sentOn.set(Thread.currentThread().getName());
            sent.set(info);
        }).get(5, TimeUnit.SECONDS);
        assertEquals("specs-test-off", sentOn.get());
        assertNotEquals(Thread.currentThread().getName(), sentOn.get());
        assertEquals("cpu", sent.get().cpu());
    }

    @Test
    void sendWaitsForTheSummaryAndStillRunsOnTheExecutor() throws Exception {
        CompletableFuture<SysInfo.Hardware> hw = new CompletableFuture<>();
        AtomicReference<String> sentOn = new AtomicReference<>();
        CompletableFuture<Void> done = SpecsDispatch.sendWhenReady(hw, off, SpecsDispatchTest::build,
                info -> sentOn.set(Thread.currentThread().getName()));
        assertFalse(done.isDone());
        assertTrue(sentOn.get() == null, "nothing is sent before the summary exists");
        hw.complete(PC);
        done.get(5, TimeUnit.SECONDS);
        assertEquals("specs-test-off", sentOn.get());
    }
}
