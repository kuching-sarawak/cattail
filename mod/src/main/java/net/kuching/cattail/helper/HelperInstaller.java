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
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts the bundled helper to {@code <gameDir>/cattail/bin/<version>/<binaryName>}.
 * The file is written to a temp file in the same directory, verified against the expected
 * SHA-256 and only then moved into place, so a half-written or tampered exe never sits at the
 * final path. An existing file is re-verified and re-extracted if it does not match.
 * {@code <gameDir>/cattail/bin/.lock} is held for the whole operation so two game instances
 * sharing a game dir do not race.
 *
 * <p>On every file store that supports POSIX modes (Linux, macOS) the helper is owner-only: the
 * temp file is created owner-only and set to {@code rwx------} before the move, whatever the
 * umask, so another local account can neither read nor run the player's helper at any point. The
 * directories made for it ({@code cattail/}, {@code cattail/bin} and the version directory) are
 * created {@code rwx------} too, as the helper makes its own; one that already exists keeps its
 * mode. An existing copy there counts as intact only with exactly that mode, so a widened or
 * de-executed copy is replaced on the next install. A store that reports POSIX support but
 * refuses mode changes has no modes to protect, so there, as on a store without POSIX modes
 * (Windows), the file is only marked executable. On Linux, FAT and exFAT usually accept chmod and
 * silently keep the mount's mode, and refuse it with EPERM only when the player does not own the
 * mount. Either way no exception escapes, and the copy is re-extracted on the next install.
 */
public final class HelperInstaller {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
    /** A single path component: no separators, no leading dot. */
    private static final Pattern BINARY_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Set<PosixFilePermission> OWNER_ONLY =
            Set.copyOf(PosixFilePermissions.fromString("rwx------"));
    /** FileChannel.lock is per JVM process; this serialises threads inside one JVM. */
    private static final Object JVM_LOCK = new Object();

    private HelperInstaller() {
    }

    /**
     * Installs the helper and returns the path of the verified exe.
     *
     * @param binaryName     the file name inside the version directory, a
     *                       {@link HelperTarget#binaryName()}: 1 to 64 ASCII letters, digits, dots,
     *                       underscores or hyphens, starting with a letter or digit (so no leading
     *                       dot) and without {@code ..}
     * @param exe            the bundled exe; not read when an intact copy already exists; not closed
     * @param expectedSha256 64 hex characters, any case
     * @throws IllegalArgumentException when the hash, the version or the binary name is malformed;
     *                                  thrown before anything is created or written
     * @throws IOException on any I/O failure or when the bundled bytes do not match the hash
     */
    public static Path install(Path gameDir, String version, String binaryName, InputStream exe,
            String expectedSha256) throws IOException {
        if (expectedSha256 == null || !SHA256.matcher(expectedSha256).matches()) {
            throw new IllegalArgumentException("expected SHA-256 must be 64 hex characters");
        }
        requireSafeComponent(VERSION, version, "version");
        requireSafeComponent(BINARY_NAME, binaryName, "binary name");
        String expected = expectedSha256.toLowerCase(Locale.ROOT);
        Path binDir = gameDir.resolve("cattail").resolve("bin");
        Path versionDir = binDir.resolve(version);
        Path target = versionDir.resolve(binaryName);

        synchronized (JVM_LOCK) {
            // The directories still to be made land on the store of the nearest one that exists.
            boolean posix = Files.getFileStore(nearestExisting(versionDir))
                    .supportsFileAttributeView(PosixFileAttributeView.class);
            if (posix) {
                // cattail/, cattail/bin and the version directory are owner-only like the helper.
                // Only directories made here get the mode (the umask can only narrow it); one that
                // already exists keeps its mode, which is acceptable: the helper in it is owner-only.
                Files.createDirectories(versionDir, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } else {
                Files.createDirectories(versionDir);
            }
            try (FileChannel lockChannel = FileChannel.open(binDir.resolve(".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = lockChannel.lock()) {
                if (Files.isRegularFile(target)
                        && (!posix || OWNER_ONLY.equals(Files.getPosixFilePermissions(target)))
                        && expected.equals(sha256Of(target))) {
                    return target;
                }
                Path temp = Files.createTempFile(versionDir, binaryName + ".", ".tmp");
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
                    makeExecutable(temp, posix);
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

    /**
     * Sets {@code rwx------} where the store keeps modes; anywhere else only marks the file executable.
     * Package-private for the test of a refused chmod.
     */
    static void makeExecutable(Path file, boolean posix) {
        if (posix) {
            try {
                Files.setPosixFilePermissions(file, OWNER_ONLY);
                return;
            } catch (IOException e) {
                // On Linux, FAT and exFAT usually accept chmod and silently keep the mount's mode,
                // and refuse it with EPERM only when the player does not own the mount. Either way
                // no exception escapes, and the copy is re-extracted on the next install. Such a
                // store has no modes to protect, and a player whose instance folder sits on a USB
                // stick must still get a working helper, so fall back to setExecutable.
            }
        }
        file.toFile().setExecutable(true);
    }

    /** {@code dir} if it exists, else its nearest existing ancestor (the root at the latest). */
    private static Path nearestExisting(Path dir) {
        Path p = dir.toAbsolutePath();
        while (!Files.exists(p) && p.getParent() != null) {
            p = p.getParent();
        }
        return p;
    }

    /** Refuses anything but a single safe path component: null, a pattern mismatch or {@code ..}. */
    private static void requireSafeComponent(Pattern pattern, String value, String what) {
        if (value == null || !pattern.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException("unsafe helper " + what + ": " + value);
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
