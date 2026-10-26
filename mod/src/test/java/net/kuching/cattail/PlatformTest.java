package net.kuching.cattail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlatformTest {

    @Test
    void windowsAmd64IsSupported() {
        assertTrue(Platform.isWindowsAmd64("Windows 11", "amd64"));
        assertTrue(Platform.isWindowsAmd64("Windows 10", "amd64"));
        assertTrue(Platform.isWindowsAmd64("Windows Server 2022", "amd64"));
    }

    @Test
    void windowsOtherArchIsNotSupported() {
        assertFalse(Platform.isWindowsAmd64("Windows 11", "aarch64"));
        assertFalse(Platform.isWindowsAmd64("Windows 11", "x86"));
    }

    @Test
    void otherOsIsNotSupported() {
        assertFalse(Platform.isWindowsAmd64("Linux", "amd64"));
        assertFalse(Platform.isWindowsAmd64("Mac OS X", "amd64"));
        assertFalse(Platform.isWindowsAmd64("windows 11", "amd64"));
    }

    @Test
    void nullPropertiesAreNotSupported() {
        assertFalse(Platform.isWindowsAmd64(null, "amd64"));
        assertFalse(Platform.isWindowsAmd64("Windows 11", null));
    }

    @Test
    void currentJvmMatchesSystemProperties() {
        boolean expected = System.getProperty("os.name", "").startsWith("Windows")
                && "amd64".equals(System.getProperty("os.arch"));
        assertTrue(Platform.isWindowsAmd64() == expected);
    }
}
