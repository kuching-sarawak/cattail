package net.kuching.cattail.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link HelperTarget} against {@code HELPER_TARGETS} in scripts/build.sh. The bundled helpers are
 * listed four times: {@code HELPER_TARGETS} in scripts/build.sh (what is built),
 * {@code helperTargets} in mod/build.gradle (what processResources requires and the jar allowlist
 * accepts), {@code HELPER_ENTRIES} in scripts/release-public.sh (what the release gate accepts)
 * and this enum (what the mod looks for on a player's PC). The three build-side lists check each
 * other on every scripts/build.sh run and release: a helper one of them lacks fails
 * processResources, verifyBundledVoiceJar or the jar gate. Nothing in the build reads the enum,
 * so a target added to one side only would show first on a player's PC, as a missing helper
 * resource or as a bundled helper the mod never picks. This test is the only check for the Java
 * side: every {@code <goos>/<goarch>} entry has exactly one constant with resource directory
 * {@code <goos>-<goarch>} and the binary name scripts/build.sh gives it, and every constant has
 * an entry.
 */
class HelperTargetBuildListTest {

    /** scripts/build.sh, copied onto the test classpath by processTestResources. */
    private static final String BUILD_SH = "repo/scripts/build.sh";
    /** The one line scripts/build.sh (and scripts/gen-helper-notices.sh) reads the list from. */
    private static final Pattern LIST_LINE = Pattern.compile("(?m)^HELPER_TARGETS=\\((.*)\\)$");
    private static final Pattern ENTRY = Pattern.compile("([a-z0-9]+)/([a-z0-9]+)");

    @Test
    void helperTargetMatchesTheBuildListOneToOne() throws IOException {
        assertOneToOne(resourceText(BUILD_SH));
    }

    @Test
    void aBuildTargetWithoutAConstantIsNamed() {
        AssertionError e = assertThrows(AssertionError.class,
                () -> assertOneToOne("HELPER_TARGETS=(windows/amd64 linux/amd64 darwin/arm64 linux/arm64)\n"));
        assertTrue(e.getMessage().contains("linux/arm64"), e.getMessage());
    }

    @Test
    void aConstantWithoutABuildTargetIsNamed() {
        AssertionError e = assertThrows(AssertionError.class,
                () -> assertOneToOne("HELPER_TARGETS=(windows/amd64 linux/amd64)\n"));
        assertTrue(e.getMessage().contains("DARWIN_ARM64"), e.getMessage());
    }

    /**
     * Fails, naming the mismatch, unless {@code buildSh} has exactly one {@code HELPER_TARGETS=(...)}
     * line and its entries and the {@link HelperTarget} constants match one to one.
     */
    static void assertOneToOne(String buildSh) {
        Matcher line = LIST_LINE.matcher(buildSh);
        List<String> lists = new ArrayList<>();
        while (line.find()) {
            lists.add(line.group(1));
        }
        assertEquals(1, lists.size(), "scripts/build.sh must have exactly one line HELPER_TARGETS=(...)");
        String list = lists.get(0).trim();
        assertFalse(list.isEmpty(), "HELPER_TARGETS in scripts/build.sh is empty");
        Set<HelperTarget> unmatched = EnumSet.allOf(HelperTarget.class);
        for (String entry : list.split("\\s+")) {
            Matcher m = ENTRY.matcher(entry);
            assertTrue(m.matches(), "HELPER_TARGETS entry " + entry + " is not <goos>/<goarch>");
            String goos = m.group(1);
            String dir = goos + "-" + m.group(2);
            List<HelperTarget> withDir = Arrays.stream(HelperTarget.values())
                    .filter(t -> t.resourceDir().equals(dir))
                    .toList();
            assertEquals(1, withDir.size(), "HELPER_TARGETS entry " + entry
                    + " needs exactly one HelperTarget whose resourceDir() is " + dir + ", found " + withDir);
            HelperTarget target = withDir.get(0);
            assertTrue(unmatched.remove(target), "HELPER_TARGETS lists " + entry + " more than once");
            String binary = "windows".equals(goos) ? "cattail-helper.exe" : "cattail-helper";
            assertEquals(binary, target.binaryName(), "binaryName() of " + target + ", the HelperTarget for "
                    + "HELPER_TARGETS entry " + entry);
        }
        assertTrue(unmatched.isEmpty(), "HelperTarget " + unmatched + " has no entry in HELPER_TARGETS in "
                + "scripts/build.sh");
    }

    private static String resourceText(String resource) throws IOException {
        try (InputStream in = HelperTargetBuildListTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is not on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
