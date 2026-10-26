package net.kuching.cattail.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Extracts the bundled helper to {@code <gameDir>/cattail/bin/<version>/cattail-helper.exe}.
 * The file is written to a temp file in the same directory, verified against the expected
 * SHA-256 and only then moved into place, so a half-written or tampered exe never sits at the
 * final path. An existing file is re-verified and re-extracted if it does not match.
 * {@code <gameDir>/cattail/bin/.lock} is held for the whole operation so two game instances
 * sharing a game dir do not race.
 */
public final class HelperInstaller {

    static final String EXE_NAME = "cattail-helper.exe";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
    /** FileChannel.lock is per JVM process; this serialises threads inside one JVM. */
    private static final Object JVM_LOCK = new Object();

    private HelperInstaller() {
    }

    /**
     * Installs the helper and returns the path of the verified exe.
     *
     * @param exe            the bundled exe; not read when an intact copy already exists; not closed
     * @param expectedSha256 64 hex characters, any case
     * @throws IOException on any I/O failure or when the bundled bytes do not match the hash
     */
    public static Path install(Path gameDir, String version, InputStream exe, String expectedSha256)
            throws IOException {
        if (expectedSha256 == null || !SHA256.matcher(expectedSha256).matches()) {
            throw new IllegalArgumentException("expected SHA-256 must be 64 hex characters");
        }
        if (version == null || !VERSION.matcher(version).matches() || version.contains("..")) {
            throw new IllegalArgumentException("unsafe helper version: " + version);
        }
        String expected = expectedSha256.toLowerCase(Locale.ROOT);
        Path binDir = gameDir.resolve("cattail").resolve("bin");
        Path versionDir = binDir.resolve(version);
        Path target = versionDir.resolve(EXE_NAME);

        synchronized (JVM_LOCK) {
            Files.createDirectories(versionDir);
            try (FileChannel lockChannel = FileChannel.open(binDir.resolve(".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = lockChannel.lock()) {
                if (Files.isRegularFile(target) && expected.equals(sha256Of(target))) {
                    return target;
                }
                Path temp = Files.createTempFile(versionDir, EXE_NAME + ".", ".tmp");
                try {
                    String actual;
                    try (OutputStream out = Files.newOutputStream(temp);
                            DigestOutputStream digestOut = new DigestOutputStream(out, newDigest())) {
                        exe.transferTo(digestOut);
                        digestOut.flush();
                        actual = HexFormat.of().formatHex(digestOut.getMessageDigest().digest());
                    }
                    if (!expected.equals(actual)) {
                        throw new IOException("bundled helper sha256 mismatch: expected " + expected
                                + ", got " + actual);
                    }
                    temp.toFile().setExecutable(true);
                    try {
                        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }
                return target;
            }
        }
    }

    static String sha256Of(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }
}
