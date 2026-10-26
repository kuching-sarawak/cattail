package net.kuching.cattail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Test;

class CattailConfigTest {

    /**
     * On the first launch NeoForge corrects an EMPTY config against the spec, which calls every
     * value's validator with {@code null}. Found live on a Windows client (2026-10-20): {@code List.of(...)}
     * threw NullPointerException from {@code contains(null)} and mod loading failed.
     */
    @Test
    void correctingAnEmptyConfigFillsDefaultsWithoutThrowing() {
        CommentedConfig empty = CommentedConfig.inMemory();
        CattailConfig.SPEC.correct(empty);
        assertTrue(CattailConfig.SPEC.isCorrect(empty));
        assertEquals("info", empty.get("helperLogLevel"));
        assertEquals(true, empty.get("enabled"));
        assertEquals(false, empty.get("forceRelay"));
        assertEquals(true, empty.get("shareSpecs"));
    }

    @Test
    void helperLogLevelRejectsUnknownValuesAndNull() {
        assertTrue(CattailConfig.SPEC.isCorrect(configWith("helperLogLevel", "warn")));
        assertFalse(CattailConfig.SPEC.isCorrect(configWith("helperLogLevel", "trace")));
    }

    private static CommentedConfig configWith(String key, Object value) {
        CommentedConfig c = CommentedConfig.inMemory();
        CattailConfig.SPEC.correct(c);
        c.set(key, value);
        return c;
    }
}
