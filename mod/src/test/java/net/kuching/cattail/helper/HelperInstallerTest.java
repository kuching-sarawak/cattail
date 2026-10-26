package net.kuching.cattail.helper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
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
        Path exe = HelperInstaller.install(gameDir, "1.2.3", new ByteArrayInputStream(EXE), sha256(EXE));

        assertEquals(gameDir.resolve("cattail/bin/1.2.3/cattail-helper.exe"), exe);
        assertArrayEquals(EXE, Files.readAllBytes(exe));
        assertEquals(List.of("cattail-helper.exe"), filesIn(exe.getParent()), "no temp files left");
        assertTrue(Files.exists(gameDir.resolve("cattail/bin/.lock")));
    }

    @Test
    void acceptsUppercaseHash() throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1", new ByteArrayInputStream(EXE),
                sha256(EXE).toUpperCase(java.util.Locale.ROOT));
        assertArrayEquals(EXE, Files.readAllBytes(exe));
    }

    @Test
    void intactExistingFileIsKeptWithoutReadingTheStream() throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1", new ByteArrayInputStream(EXE), sha256(EXE));
        ByteArrayInputStream second = new ByteArrayInputStream(EXE);

        assertEquals(exe, HelperInstaller.install(gameDir, "1", second, sha256(EXE)));
        assertEquals(EXE.length, second.available(), "stream must not be consumed when the file is intact");
    }

    @Test
    void corruptedFileIsRewritten() throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1", new ByteArrayInputStream(EXE), sha256(EXE));
        Files.write(exe, "corrupt".getBytes(StandardCharsets.US_ASCII));

        Path again = HelperInstaller.install(gameDir, "1", new ByteArrayInputStream(EXE), sha256(EXE));

        assertEquals(exe, again);
        assertArrayEquals(EXE, Files.readAllBytes(again));
        assertEquals(List.of("cattail-helper.exe"), filesIn(exe.getParent()));
    }

    @Test
    void wrongExpectedHashThrowsAndLeavesNothingBehind() throws Exception {
        String wrong = sha256("something else".getBytes(StandardCharsets.US_ASCII));

        IOException e = assertThrows(IOException.class,
                () -> HelperInstaller.install(gameDir, "1", new ByteArrayInputStream(EXE), wrong));

        assertTrue(e.getMessage().contains("sha256"), e.getMessage());
        assertEquals(List.of(), filesIn(gameDir.resolve("cattail/bin/1")));
    }

    @Test
    void badBundledBytesNeverReplaceTheExistingFile() throws Exception {
        Path exe = HelperInstaller.install(gameDir, "1", new ByteArrayInputStream(EXE), sha256(EXE));
        byte[] corrupt = "corrupt".getBytes(StandardCharsets.US_ASCII);
        Files.write(exe, corrupt);
        byte[] tampered = "tampered".getBytes(StandardCharsets.US_ASCII);

        assertThrows(IOException.class,
                () -> HelperInstaller.install(gameDir, "1", new ByteArrayInputStream(tampered), sha256(EXE)));

        assertArrayEquals(corrupt, Files.readAllBytes(exe), "a failed verify must not touch the existing file");
        assertEquals(List.of("cattail-helper.exe"), filesIn(exe.getParent()), "temp file removed");
    }

    @Test
    void rejectsMalformedHashAndUnsafeVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> HelperInstaller.install(gameDir, "1", new ByteArrayInputStream(EXE), "abc"));
        assertThrows(IllegalArgumentException.class,
                () -> HelperInstaller.install(gameDir, "../x", new ByteArrayInputStream(EXE), sha256(EXE)));
        assertThrows(IllegalArgumentException.class,
                () -> HelperInstaller.install(gameDir, "", new ByteArrayInputStream(EXE), sha256(EXE)));
        assertFalse(Files.exists(gameDir.resolve("x")));
    }
}
