package net.kuching.cattail;

import java.lang.reflect.Proxy;
import java.util.List;
import org.slf4j.Logger;

/** Test support: an SLF4J {@link Logger} that records calls instead of printing them. */
public final class RecordingLogger {

    private RecordingLogger() {}

    /** A Logger that records "level|format|args" into {@code out} for every call; nothing else. */
    public static Logger into(List<String> out) {
        return (Logger) Proxy.newProxyInstance(
                RecordingLogger.class.getClassLoader(), new Class<?>[] {Logger.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("is")) {
                        return true; // every level enabled
                    }
                    if (List.of("trace", "debug", "info", "warn", "error").contains(name)) {
                        StringBuilder b = new StringBuilder(name);
                        for (Object a : args) {
                            b.append('|').append(a);
                        }
                        out.add(b.toString());
                    }
                    if (name.equals("getName")) {
                        return "recording";
                    }
                    return null;
                });
    }
}
