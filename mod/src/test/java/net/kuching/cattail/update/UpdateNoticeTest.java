package net.kuching.cattail.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UpdateNoticeTest {

    @Test
    void numericPartsCompareAsNumbers() {
        assertTrue(UpdateNotice.isNewer("0.2.0", "0.1.0"));
        assertTrue(UpdateNotice.isNewer("0.10.0", "0.9.9"));
        assertTrue(UpdateNotice.isNewer("1.0.0", "0.99.99"));
        assertTrue(UpdateNotice.isNewer("0.1.1", "0.1"));
        assertFalse(UpdateNotice.isNewer("0.1.0", "0.1.0"));
        assertFalse(UpdateNotice.isNewer("0.1.0", "0.1"));
        assertFalse(UpdateNotice.isNewer("0.1.0", "0.2.0"));
    }

    @Test
    void suffixesAreIgnoredAndUnknownIsNeverNewer() {
        assertTrue(UpdateNotice.isNewer("0.2.0-dirty", "0.1.0+mc1.21.1"));
        assertFalse(UpdateNotice.isNewer("0.1.0-dirty", "0.1.0"));
        assertFalse(UpdateNotice.isNewer("?", "0.1.0"));
        assertFalse(UpdateNotice.isNewer("0.2.0", "?"));
        assertFalse(UpdateNotice.isNewer("", ""));
        assertFalse(UpdateNotice.isNewer("v0.2.0", "0.1.0"));
    }

    @Test
    void noticeOnlyWhenServerIsNewerAndOncePerServer() {
        Set<String> shown = new HashSet<>();
        assertEquals(Optional.empty(), UpdateNotice.decide("0.1.0", "0.1.0", "mc.example.com", shown));
        assertEquals(Optional.empty(), UpdateNotice.decide("0.1.0", "0.2.0", "mc.example.com", shown));
        assertTrue(shown.isEmpty(), "an older server adds nothing");
        Optional<String> first = UpdateNotice.decide("0.2.0", "0.1.0", "mc.example.com", shown);
        assertEquals(Optional.of("This server runs Cattail 0.2.0; you have 0.1.0. Ask the server owner for the new jar."),
                first);
        assertEquals(Optional.empty(), UpdateNotice.decide("0.2.0", "0.1.0", "mc.example.com", shown), "once per server");
        assertTrue(UpdateNotice.decide("0.2.0", "0.1.0", "other.example.com", shown).isPresent(), "another server");
    }
}
