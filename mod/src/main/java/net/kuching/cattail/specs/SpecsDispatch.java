package net.kuching.cattail.specs;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * The concurrency policy of the client's specs send, kept free of Minecraft classes so it is
 * unit-tested; {@link SpecsClient} is the glue around it.
 *
 * <p>Two rules. The hardware summary is read once per game process on its own daemon thread,
 * and that read can never stall a login: the future completes with {@link #UNKNOWN} when the read
 * throws or outlives {@link #READ_TIMEOUT} (a wedged WMI provider on Windows). And the payload is
 * built and sent on the given executor, never on the thread that asked for it: the previous
 * version queued the send on the render thread after the read, so a first login whose read was
 * still running put the send behind the client's post-login work (on a large modpack tens of
 * seconds of JEI start-up) and the specs reached the server up to 85 s after the join. A
 * {@code Connection.send} from another thread is safe: it hands the packet to the Netty event loop.
 */
final class SpecsDispatch {

    /** The summary sent when the hardware cannot be read in time. */
    static final SysInfo.Hardware UNKNOWN = new SysInfo.Hardware("unknown", 0, 0, 0, List.of());
    /** WMI takes a few seconds on a healthy Windows PC; well past that, send without the hardware. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private SpecsDispatch() {
    }

    /**
     * Starts {@code read} on a daemon thread and returns the future of its result, which is
     * {@link #UNKNOWN} (and a WARN line) when the read throws, and {@link #UNKNOWN} when it has not
     * finished after {@code timeout}; the thread itself is left to finish or hang on its own.
     */
    static CompletableFuture<SysInfo.Hardware> readAsync(Supplier<SysInfo.Hardware> read, Duration timeout,
            Logger log) {
        CompletableFuture<SysInfo.Hardware> future = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                future.complete(read.get());
            } catch (Throwable e) { // OSHI/JNA errors must never break a login
                log.warn("Cattail could not read the hardware summary: {}", e.toString());
                future.complete(UNKNOWN);
            }
        }, "cattail-sysinfo");
        t.setDaemon(true);
        t.start();
        // completeOnTimeout returns this same future; its one timer thread is JDK-shared and daemon.
        return future.completeOnTimeout(UNKNOWN, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Once {@code hardware} is done, builds the specs with {@code build} and hands them to
     * {@code send}, both on {@code off}: never on the calling thread, even when the future is
     * already complete, so a slow read and a busy caller can neither delay nor be delayed by it.
     *
     * @return completes when the send has run (or failed); callers need not wait on it
     */
    static CompletableFuture<Void> sendWhenReady(CompletableFuture<SysInfo.Hardware> hardware, Executor off,
            Function<SysInfo.Hardware, SysInfo> build, Consumer<SysInfo> send) {
        return hardware.thenAcceptAsync(hw -> send.accept(build.apply(hw)), off);
    }
}
