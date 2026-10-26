package net.kuching.cattail.serverlist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerListInfoSourceTest {

    @BeforeEach
    @AfterEach
    void reset() {
        ServerListInfoSource.uninstall();
    }

    /** A client's integrated server never installs a source: its status carries nothing. */
    @Test
    void emptyUntilInstalled() {
        assertEquals(Optional.empty(), ServerListInfoSource.current());
    }

    /** Asked on every status rebuild, so a reloaded config shows without reinstalling. */
    @Test
    void returnsTheInstalledSourcesValueEachTime() {
        AtomicReference<Optional<ServerListInfo>> value =
                new AtomicReference<>(Optional.of(new ServerListInfo("0.2.0", List.of("Voice chat on"))));
        ServerListInfoSource.install(value::get);
        assertEquals(Optional.of(new ServerListInfo("0.2.0", List.of("Voice chat on"))), ServerListInfoSource.current());
        value.set(Optional.of(new ServerListInfo("0.2.0", List.of("Changed"))));
        assertEquals(Optional.of(new ServerListInfo("0.2.0", List.of("Changed"))), ServerListInfoSource.current());
        value.set(Optional.empty());
        assertEquals(Optional.empty(), ServerListInfoSource.current());
    }

    /** It runs inside the server tick: a failing or null source gives no info rather than crashing the server. */
    @Test
    void aFailingOrNullSourceGivesNoInfo() {
        ServerListInfoSource.install(() -> {
            throw new IllegalStateException("Cannot get config value before config is loaded.");
        });
        assertEquals(Optional.empty(), ServerListInfoSource.current());
        assertEquals(Optional.empty(), ServerListInfoSource.current());
        ServerListInfoSource.install(() -> null);
        assertEquals(Optional.empty(), ServerListInfoSource.current());
    }
}
