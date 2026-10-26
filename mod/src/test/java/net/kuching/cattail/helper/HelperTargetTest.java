package net.kuching.cattail.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class HelperTargetTest {

    @Test
    void windowsAmd64() {
        assertEquals(Optional.of(HelperTarget.WINDOWS_AMD64), HelperTarget.detect("Windows 11", "amd64"));
        assertEquals(Optional.of(HelperTarget.WINDOWS_AMD64), HelperTarget.detect("Windows Server 2022", "amd64"));
    }

    @Test
    void linuxAmd64() {
        assertEquals(Optional.of(HelperTarget.LINUX_AMD64), HelperTarget.detect("Linux", "amd64"));
    }

    @Test
    void macAppleSilicon() {
        assertEquals(Optional.of(HelperTarget.DARWIN_ARM64), HelperTarget.detect("Mac OS X", "aarch64"));
    }

    @Test
    void rosettaJvmIsUnsupported() {
        // What an x86-64 JDK reports on macOS, under Rosetta as on an Intel Mac.
        assertEquals(Optional.empty(), HelperTarget.detect("Mac OS X", "x86_64"));
    }

    @Test
    void otherCombinationsAreUnsupported() {
        String[][] combinations = {
            {"Windows 11", "aarch64"},
            {"Windows 11", "x86"},
            {"Linux", "aarch64"},
            {"windows 11", "amd64"},
            {"FreeBSD", "amd64"},
            {"Mac OS X", "amd64"},
        };
        for (String[] c : combinations) {
            assertEquals(Optional.empty(), HelperTarget.detect(c[0], c[1]), c[0] + "/" + c[1]);
        }
    }

    @Test
    void nullsAreUnsupported() {
        assertEquals(Optional.empty(), HelperTarget.detect(null, "amd64"));
        assertEquals(Optional.empty(), HelperTarget.detect("Linux", null));
    }

    @Test
    void namesMatchTheSpec() {
        assertEquals("windows-amd64", HelperTarget.WINDOWS_AMD64.resourceDir());
        assertEquals("cattail-helper.exe", HelperTarget.WINDOWS_AMD64.binaryName());
        assertEquals("linux-amd64", HelperTarget.LINUX_AMD64.resourceDir());
        assertEquals("cattail-helper", HelperTarget.LINUX_AMD64.binaryName());
        assertEquals("darwin-arm64", HelperTarget.DARWIN_ARM64.resourceDir());
        assertEquals("cattail-helper", HelperTarget.DARWIN_ARM64.binaryName());
    }

    @Test
    void currentMatchesSystemProperties() {
        assertEquals(HelperTarget.detect(System.getProperty("os.name"), System.getProperty("os.arch")),
                HelperTarget.current());
    }
}
