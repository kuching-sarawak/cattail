package net.kuching.cattail.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class HelperInstallerTest {

    private static final byte[] EXE = "MZ fake helper binary".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path gameDir;

    static String sha256(byte[] data) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private List<String> filesIn(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void freshInstallWritesVerifiedFileUnderVersionDir() throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1.2.3", "cattail-helper.exe", new ByteArrayInputStream(EXE),
                sha256(EXE));

        assertEquals(gameDir.resolve("cattail/bin/1.2.3/cattail-helper.exe"), exe);
        assertArrayEquals(EXE, Files.readAllBytes(exe));
        assertEquals(List.of("cattail-helper.exe"), filesIn(exe.getParent()), "no temp files left");
        assertTrue(Files.exists(gameDir.resolve("cattail/bin/.lock")));
    }

    @Test
    void acceptsUppercaseHash() throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1", "cattail-helper.exe", new ByteArrayInputStream(EXE),
                sha256(EXE).toUpperCase(java.util.Locale.ROOT));
        assertArrayEquals(EXE, Files.readAllBytes(exe));
    }

    @Test
    void intactExistingFileIsKeptWithoutReadingTheStream() throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1", "cattail-helper.exe", new ByteArrayInputStream(EXE),
                sha256(EXE));
        ByteArrayInputStream second = new ByteArrayInputStream(EXE);

        assertEquals(exe, HelperInstaller.install(gameDir, "1", "cattail-helper.exe", second, sha256(EXE)));
        assertEquals(EXE.length, second.available(), "stream must not be consumed when the file is intact");
    }

    @Test
    void corruptedFileIsRewritten() throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1", "cattail-helper.exe", new ByteArrayInputStream(EXE),
                sha256(EXE));
        Files.write(exe, "corrupt".getBytes(StandardCharsets.US_ASCII));

        Path again = HelperInstaller.install(gameDir, "1", "cattail-helper.exe", new ByteArrayInputStream(EXE),
                sha256(EXE));

        assertEquals(exe, again);
        assertArrayEquals(EXE, Files.readAllBytes(again));
        assertEquals(List.of("cattail-helper.exe"), filesIn(exe.getParent()));
    }

    @Test
    void wrongExpectedHashThrowsAndLeavesNothingBehind() throws Exception {
        String wrong = sha256("something else".getBytes(StandardCharsets.US_ASCII));

        IOException e = assertThrows(IOException.class,
                () -> HelperInstaller.install(gameDir, "1", "cattail-helper.exe", new ByteArrayInputStream(EXE),
                        wrong));

        assertTrue(e.getMessage().contains("sha256"), e.getMessage());
        assertEquals(List.of(), filesIn(gameDir.resolve("cattail/bin/1")));
    }

    @Test
    void badBundledBytesNeverReplaceTheExistingFile() throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1", "cattail-helper.exe", new ByteArrayInputStream(EXE),
                sha256(EXE));
        byte[] corrupt = "corrupt".getBytes(StandardCharsets.US_ASCII);
        Files.write(exe, corrupt);
        byte[] tampered = "tampered".getBytes(StandardCharsets.US_ASCII);

        assertThrows(IOException.class,
                () -> HelperInstaller.install(gameDir, "1", "cattail-helper.exe", new ByteArrayInputStream(tampered),
                        sha256(EXE)));

        assertArrayEquals(corrupt, Files.readAllBytes(exe), "a failed verify must not touch the existing file");
        assertEquals(List.of("cattail-helper.exe"), filesIn(exe.getParent()), "temp file removed");
    }

    @Test
    void rejectsMalformedHashAndUnsafeVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> HelperInstaller.install(gameDir, "1", "cattail-helper.exe", new ByteArrayInputStream(EXE),
                        "abc"));
        assertThrows(IllegalArgumentException.class,
                () -> HelperInstaller.install(gameDir, "../x", "cattail-helper.exe", new ByteArrayInputStream(EXE),
                        sha256(EXE)));
        assertThrows(IllegalArgumentException.class,
                () -> HelperInstaller.install(gameDir, "", "cattail-helper.exe", new ByteArrayInputStream(EXE),
                        sha256(EXE)));
        assertFalse(Files.exists(gameDir.resolve("x")));
    }

    @Test
    void installsUnderTheGivenBinaryName() throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1.2.3", "cattail-helper", new ByteArrayInputStream(EXE),
                sha256(EXE));

        assertEquals(gameDir.resolve("cattail/bin/1.2.3/cattail-helper"), exe);
        assertEquals(List.of("cattail-helper"), filesIn(exe.getParent()));
    }

    @Test
    void installRefusesUnsafeBinaryName() throws Exception {
        for (String bad : new String[] {"../x", "a/b", "a\\b", "", ".hidden", "x".repeat(65), null, "a..b"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> HelperInstaller.install(gameDir, "1", bad, new ByteArrayInputStream(EXE), sha256(EXE)),
                    String.valueOf(bad));
        }
        assertEquals(List.of(), filesIn(gameDir.resolve("cattail/bin/1")), "nothing written");
        assertFalse(Files.exists(gameDir.resolve("cattail")), "refused before any directory is created");
    }

    @Test
    void installedFileIsOwnerOnlyOnPosix() throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1", "cattail-helper", new ByteArrayInputStream(EXE),
                sha256(EXE));

        assumeTrue(Files.getFileStore(exe).supportsFileAttributeView(PosixFileAttributeView.class));
        // Pins the resulting mode, not the explicit chmod: createTempFile plus setExecutable(true) also gives it.
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(exe));
    }

    @Test
    void freshDirectoriesAreOwnerOnlyOnPosix() throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1", "cattail-helper", new ByteArrayInputStream(EXE),
                sha256(EXE));

        assumeTrue(Files.getFileStore(exe).supportsFileAttributeView(PosixFileAttributeView.class));
        for (String dir : new String[] {"cattail", "cattail/bin", "cattail/bin/1"}) {
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(gameDir.resolve(dir)), dir);
        }
    }

    @Test
    void installAcceptsBinaryNameOfSixtyFourCharacters() throws Exception {
        String longest = "x".repeat(64);

        Path exe = HelperInstaller.install(gameDir, "1", longest, new ByteArrayInputStream(EXE), sha256(EXE));

        assertEquals(gameDir.resolve("cattail/bin/1").resolve(longest), exe);
        assertArrayEquals(EXE, Files.readAllBytes(exe));
    }

    @Test
    void deExecutedCopyIsReextractedOwnerOnly() throws Exception {
        assertCopyWithModeIsReextracted("rw-r--r--");
    }

    @Test
    void widenedCopyIsReextractedOwnerOnly() throws Exception {
        assertCopyWithModeIsReextracted("rwxrwxrwx");
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void refusedChmodFallsBackWithoutFailing() throws Exception {
        // chflags uchg makes chmod fail with EPERM, the error a Linux FAT drive gives when the player
        // does not own the mount.
        Path file = Files.createFile(gameDir.resolve("immutable"));
        run("chflags", "uchg", file.toString());
        try {
            assertThrows(FileSystemException.class,
                    () -> Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------")),
                    "precondition: chmod is refused");
            assertDoesNotThrow(() -> HelperInstaller.makeExecutable(file, true));
        } finally {
            run("chflags", "nouchg", file.toString());
        }
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).inheritIO().start();
        assertEquals(0, process.waitFor(), String.join(" ", command));
    }

    /** An intact copy whose mode is not rwx------ must be replaced from the stream, not kept. */
    private void assertCopyWithModeIsReextracted(String mode) throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1", "cattail-helper", new ByteArrayInputStream(EXE),
                sha256(EXE));
        assumeTrue(Files.getFileStore(exe).supportsFileAttributeView(PosixFileAttributeView.class));
        Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString(mode));
        assertEquals(PosixFilePermissions.fromString(mode), Files.getPosixFilePermissions(exe), "mode applied");
        ByteArrayInputStream second = new ByteArrayInputStream(EXE);

        assertEquals(exe, HelperInstaller.install(gameDir, "1", "cattail-helper", second, sha256(EXE)));

        assertEquals(0, second.available(), "a copy with mode " + mode + " must be re-extracted from the stream");
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(exe));
        assertArrayEquals(EXE, Files.readAllBytes(exe));
        assertEquals(List.of("cattail-helper"), filesIn(exe.getParent()), "no temp files left");
    }
}
