package net.kuching.cattail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kuching.cattail.RecordingLogger;
import net.kuching.cattail.serverlist.ServerListInfo;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * The spec itself cannot be loaded in a unit test (NeoForge's loaded-config type is sealed to its own
 * record), so {@code current} is driven through its overload with the values a corrected config
 * holds; the live read of the file is covered by {@code scripts/e2e-server-list.sh}.
 */
class ServerListConfigTest {

    private static final List<String> DEFAULT_LINES = List.of("Voice chat on", "Restarts daily 2:00 AM ET");
    private static final String DROPPED = "warn|Cattail: cattail-server.toml serverList.lines: {}|";

    private final List<String> logged = new ArrayList<>();
    private final Logger log = RecordingLogger.into(logged);

    /** First launch: NeoForge corrects an EMPTY config against the spec, calling every validator with null. */
    @Test
    void correctingAnEmptyConfigFillsDefaultsWithoutThrowing() {
        CommentedConfig defaults = defaults();
        assertTrue(ServerListConfig.SPEC.isCorrect(defaults));
        assertEquals(true, defaults.get("serverList.showIcon"));
        assertEquals(DEFAULT_LINES, defaults.get("serverList.lines"));
    }

    @Test
    void linesMayBeEmptyButHoldOnlyStrings() {
        assertTrue(ServerListConfig.SPEC.isCorrect(configWith("serverList.lines", new ArrayList<>())));
        assertFalse(ServerListConfig.SPEC.isCorrect(configWith("serverList.lines", new ArrayList<>(List.of(1, 2)))));
        assertFalse(ServerListConfig.SPEC.isCorrect(configWith("serverList.showIcon", "yes")));
    }

    @Test
    void currentOnTheDefaultsShipsTheDefaultLines() {
        CommentedConfig defaults = defaults();
        assertEquals(Optional.of(new ServerListInfo("0.2.0", DEFAULT_LINES)), ServerListConfig.current("0.2.0",
                defaults.<Boolean>get("serverList.showIcon"), defaults.<List<String>>get("serverList.lines"), log));
        assertEquals(List.of(), logged);
    }

    @Test
    void currentIsEmptyWhenShowIconIsOff() {
        assertEquals(Optional.empty(), ServerListConfig.current("0.2.0", false, DEFAULT_LINES, log));
    }

    /** {@code lines = []} with the icon on: the object still ships, with the version and no lines. */
    @Test
    void anEmptyLinesListShipsTheVersionAlone() {
        assertEquals(Optional.of(new ServerListInfo("0.2.0", List.of())),
                ServerListConfig.current("0.2.0", true, List.of(), log));
        assertEquals(List.of(), logged);
    }

    /** The status is rebuilt every 5 s: a line left out or cut is warned about once per edit, not per rebuild. */
    @Test
    void droppedLinesAreWarnedOncePerDistinctList() {
        List<String> bad = new ArrayList<>(List.of("&aVoice chat on", "", "&a&b", "y".repeat(65)));
        assertEquals(Optional.of(new ServerListInfo("0.2.0", List.of("§aVoice chat on", "y".repeat(64)))),
                ServerListConfig.current("0.2.0", true, bad, log));
        List<String> warnings = List.of(DROPPED + "line 2 is empty, left out",
                DROPPED + "line 3 has only formatting codes, left out",
                DROPPED + "line 4 is longer than 64 characters, cut");
        assertEquals(warnings, logged);

        ServerListConfig.current("0.2.0", true, new ArrayList<>(bad), log);
        assertEquals(warnings, logged);

        assertEquals(Optional.of(new ServerListInfo("0.2.0", List.of("Fixed"))),
                ServerListConfig.current("0.2.0", true, List.of("Fixed"), log));
        assertEquals(warnings, logged);

        ServerListConfig.current("0.2.0", true, new ArrayList<>(bad), log);
        assertEquals(6, logged.size());
        assertEquals(warnings, logged.subList(3, 6));
    }

    private static CommentedConfig defaults() {
        CommentedConfig c = CommentedConfig.inMemory();
        ServerListConfig.SPEC.correct(c);
        return c;
    }

    private static CommentedConfig configWith(String key, Object value) {
        CommentedConfig c = defaults();
        c.set(key, value);
        return c;
    }
}
