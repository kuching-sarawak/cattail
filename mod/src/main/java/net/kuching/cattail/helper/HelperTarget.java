package net.kuching.cattail.helper;

import java.util.Optional;

/**
 * A platform the mod jar bundles a helper for, with where that helper lives in the jar. This enum
 * is the one place in the mod that maps the JVM's {@code os.name} and {@code os.arch} to a bundled
 * helper; nothing else reads OS names to decide about the helper.
 */
public enum HelperTarget {
    /** Windows on 64-bit x86 (Go {@code windows/amd64}). */
    WINDOWS_AMD64("windows-amd64", "cattail-helper.exe"),
    /** Linux on 64-bit x86 (Go {@code linux/amd64}). */
    LINUX_AMD64("linux-amd64", "cattail-helper"),
    /** macOS on Apple silicon (Go {@code darwin/arm64}). */
    DARWIN_ARM64("darwin-arm64", "cattail-helper");

    private final String resourceDir;
    private final String binaryName;

    HelperTarget(String resourceDir, String binaryName) {
        this.resourceDir = resourceDir;
        this.binaryName = binaryName;
    }

    /**
     * The directory under {@code /helper/} in the jar that holds this target's binary and its
     * {@code cattail-helper.sha256}, such as {@code windows-amd64}.
     */
    public String resourceDir() {
        return resourceDir;
    }

    /** The file name of this target's helper binary, in the jar and once installed. */
    public String binaryName() {
        return binaryName;
    }

    /**
     * The target for a JVM that reports {@code osName} as {@code os.name} and {@code osArch} as
     * {@code os.arch}, or empty when the jar has no helper for it. This is the only method in the
     * mod that matches OS names (the {@code cattail:sysinfo} payload only reports them). The match is
     * case-sensitive: {@code os.name} starting with {@code Windows} with {@code amd64}, exactly
     * {@code Linux} with {@code amd64}, or exactly {@code Mac OS X} with {@code aarch64}; anything
     * else, including a {@code null} value, is unsupported.
     *
     * <p>An x86-64 JVM running under Rosetta on Apple silicon reports {@code Mac OS X} with
     * {@code x86_64} (OpenJDK reports {@code amd64} for x86-64 everywhere but on macOS), the same as
     * an Intel Mac. That case is deliberately unsupported: no Intel Mac helper ships, and the arm64
     * helper is picked only for an {@code aarch64} JVM.
     */
    public static Optional<HelperTarget> detect(String osName, String osArch) {
        if (osName == null || osArch == null) {
            return Optional.empty();
        }
        if (osName.startsWith("Windows") && "amd64".equals(osArch)) {
            return Optional.of(WINDOWS_AMD64);
        }
        if ("Linux".equals(osName) && "amd64".equals(osArch)) {
            return Optional.of(LINUX_AMD64);
        }
        if ("Mac OS X".equals(osName) && "aarch64".equals(osArch)) {
            return Optional.of(DARWIN_ARM64);
        }
        return Optional.empty();
    }

    /** The target for this JVM, from its {@code os.name} and {@code os.arch}; see {@link #detect}. */
    public static Optional<HelperTarget> current() {
        return detect(System.getProperty("os.name"), System.getProperty("os.arch"));
    }
}
