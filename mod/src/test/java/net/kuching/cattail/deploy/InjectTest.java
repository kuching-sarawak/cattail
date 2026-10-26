package net.kuching.cattail.deploy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InjectTest {

    private static final String MINIMAL = "authkey=tskey-auth-EXAMPLE\ntag=tag:mc-player\n";
    private static final byte[] A_BYTES = "alpha\n".getBytes(UTF_8);
    /** Larger than one copy buffer, and binary. */
    private static final byte[] B_BYTES = binary(70_000);
    private static final LocalDateTime DOS_EPOCH = LocalDateTime.of(1980, 2, 1, 0, 0);

    @TempDir
    Path dir;

    private Path jar;
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, UTF_8);

    @BeforeEach
    void createFixture() throws IOException {
        jar = fixture("cattail-9.9.9.jar", false, false);
    }

    /** Every path, success or failure: the key never reaches either stream. */
    @AfterEach
    void neverPrintsTheKey() {
        assertFalse(stdout().contains("EXAMPLE"), stdout());
        assertFalse(stderr().contains("EXAMPLE"), stderr());
    }

    @Test
    void injectsIntoNamedCopy() throws IOException {
        Path props = props(MINIMAL);
        assertEquals(0, run(jar, "inject", props.toString()));

        Path output = Inject.defaultOutput(jar, "tag:mc-player");
        assertEquals(dir.resolve("cattail-9.9.9-mc-player.jar"), output);
        assertTrue(Files.isRegularFile(output));
        Map<String, byte[]> in = entries(jar);
        Map<String, byte[]> got = entries(output);
        assertEquals(MINIMAL, new String(got.get(DeploymentSettings.RESOURCE), UTF_8));
        assertArrayEquals(A_BYTES, got.get("a.txt"));
        assertArrayEquals(B_BYTES, got.get("dir/b.bin"));
        for (Map.Entry<String, byte[]> e : in.entrySet()) {
            assertArrayEquals(e.getValue(), got.get(e.getKey()), e.getKey());
        }
        assertEquals(in.size() + 1, got.size());
        List<String> order = new ArrayList<>(in.keySet());
        order.add(DeploymentSettings.RESOURCE);
        assertEquals(order, new ArrayList<>(got.keySet())); // input order kept, the entry last

        assertTrue(stdout().contains(output.toString()), stdout());
        assertTrue(stdout().contains("tag:mc-player"), stdout());
        assertEquals(1, stdout().lines().count(), stdout());
        assertEquals("", stderr());
        assertFalse(stdout().contains("EXAMPLE"));
        assertFalse(stderr().contains("EXAMPLE"));
    }

    @Test
    void entryIsReproducible() throws IOException {
        Path props = props(MINIMAL);
        // The same settings written differently: comment, order, spaces, CRLF.
        Path other = dir.resolve("other.properties");
        Files.writeString(other, "# owner notes\r\ntag = tag:mc-player \r\nauthkey=tskey-auth-EXAMPLE\r\n", UTF_8);
        Path one = dir.resolve("one.jar");
        Path two = dir.resolve("two.jar");

        assertEquals(0, run(jar, "inject", props.toString(), "--out", one.toString()));
        assertEquals(0, run(jar, "inject", other.toString(), "--out", two.toString()));

        assertArrayEquals(Files.readAllBytes(one), Files.readAllBytes(two));
    }

    @Test
    void replacesAnExistingEntry() throws IOException {
        Path legacy = fixture("cattail-9.9.9-old.jar", false, true);
        assertEquals("legacy=1\n", new String(entries(legacy).get(DeploymentSettings.RESOURCE), UTF_8));
        Path props = props(MINIMAL);

        assertEquals(0, run(legacy, "inject", props.toString()));

        Map<String, byte[]> got = entries(Inject.defaultOutput(legacy, "tag:mc-player"));
        assertEquals(MINIMAL, new String(got.get(DeploymentSettings.RESOURCE), UTF_8));
        for (Map.Entry<String, byte[]> e : got.entrySet()) {
            assertFalse(e.getKey().contains("legacy"), e.getKey());
            assertFalse(new String(e.getValue(), UTF_8).contains("legacy"), e.getKey());
        }
        assertArrayEquals(A_BYTES, got.get("a.txt"));
        assertArrayEquals(B_BYTES, got.get("dir/b.bin"));
    }

    @Test
    void outOverridesAndRefusesExistingWithoutExplicitOut() throws IOException {
        Path props = props(MINIMAL);
        Path output = Inject.defaultOutput(jar, "tag:mc-player");
        Files.writeString(output, "keep me", UTF_8);

        assertEquals(2, run(jar, "inject", props.toString()));
        assertEquals("keep me", Files.readString(output, UTF_8));
        assertTrue(stderr().contains(output.toString()), stderr());
        assertTrue(stderr().contains("--out"), stderr());
        assertDirHolds(jar, props, output);

        reset();
        assertEquals(0, run(jar, "inject", props.toString(), "--out", output.toString()));
        assertEquals(MINIMAL, new String(entries(output).get(DeploymentSettings.RESOURCE), UTF_8));
        assertDirHolds(jar, props, output);
    }

    @Test
    void refusesOutEqualToInput() throws IOException {
        Path props = props(MINIMAL);
        byte[] before = Files.readAllBytes(jar);

        assertEquals(2, run(jar, "inject", props.toString(), "--out", jar.toString()));
        assertArrayEquals(before, Files.readAllBytes(jar));

        reset();
        Path sameFileOtherSpelling = jar.getParent().resolve(".").resolve(jar.getFileName());
        assertEquals(2, run(jar, "inject", props.toString(), "--out", sameFileOtherSpelling.toString()));
        assertArrayEquals(before, Files.readAllBytes(jar));
        assertDirHolds(jar, props);
    }

    @Test
    void refusesOutEqualToTheDeploymentFile() throws IOException {
        Path props = props(MINIMAL);
        byte[] before = Files.readAllBytes(props);

        assertEquals(2, run(jar, "inject", props.toString(), "--out", props.toString()));
        assertTrue(stderr().contains(props + " is the deployment file; give another --out"), stderr());
        assertArrayEquals(before, Files.readAllBytes(props));

        reset();
        Path sameFileOtherSpelling = props.getParent().resolve(".").resolve(props.getFileName());
        assertEquals(2, run(jar, "inject", props.toString(), "--out", sameFileOtherSpelling.toString()));
        assertArrayEquals(before, Files.readAllBytes(props));
        assertDirHolds(jar, props);
    }

    @Test
    void outputIsReadableByTheOwnerOnly() throws IOException {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class), "POSIX file store");
        Path props = props(MINIMAL);

        assertEquals(0, run(jar, "inject", props.toString()));
        assertEquals(EnumSet.of(OWNER_READ, OWNER_WRITE),
                Files.getPosixFilePermissions(Inject.defaultOutput(jar, "tag:mc-player")));

        // Overwriting an existing 0644 file: the rename puts a new inode in its place.
        Path existing = dir.resolve("existing.jar");
        Files.writeString(existing, "old", UTF_8);
        Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rw-r--r--"));
        assertEquals(0, run(jar, "inject", props.toString(), "--out", existing.toString()));
        assertEquals(EnumSet.of(OWNER_READ, OWNER_WRITE), Files.getPosixFilePermissions(existing));
        assertEquals(MINIMAL, new String(entries(existing).get(DeploymentSettings.RESOURCE), UTF_8));
    }

    @Test
    void rejectsInvalidFileWithKeyAndWritesNothing() throws IOException {
        Path props = props("authkey=tskey-auth-EXAMPLE\ntag=BAD\n");

        assertEquals(2, run(jar, "inject", props.toString()));

        assertTrue(stderr().contains("tag must match tag:[a-z0-9-]+"), stderr());
        assertEquals("", stdout());
        assertDirHolds(jar, props);
    }

    @Test
    void rejectsPastExpiry() throws IOException {
        Path props = props("authkey=tskey-auth-EXAMPLE\nauthkey.expires=2000-01-01\ntag=tag:mc-player\n");

        assertEquals(2, run(jar, "inject", props.toString()));

        assertTrue(stderr().contains("authkey.expires is in the past"), stderr());
        assertDirHolds(jar, props);
    }

    @Test
    void probeTargetsNeedTheMarker() throws IOException {
        Path props = props(MINIMAL + "probe.targets=100.101.102.103:25565\n");

        assertEquals(2, run(jar, "inject", props.toString()));
        assertTrue(stderr().contains("probe.targets is only allowed in a probe jar"), stderr());
        assertDirHolds(jar, props);

        reset();
        Path probeJar = fixture("cattail-9.9.9-probe.jar", true, false);
        assertEquals(0, run(probeJar, "inject", props.toString()));
        String entry = new String(
                entries(Inject.defaultOutput(probeJar, "tag:mc-player")).get(DeploymentSettings.RESOURCE), UTF_8);
        assertTrue(entry.contains("probe.targets=100.101.102.103:25565\n"), entry);
        assertEquals(MINIMAL + "probe.targets=100.101.102.103:25565\n", entry);
    }

    @Test
    void usageAndVersion() throws IOException {
        assertEquals(2, run(jar));
        assertTrue(stderr().contains("usage"), stderr());
        assertEquals("", stdout());

        reset();
        assertEquals(0, run(jar, "--version"));
        assertEquals("9.9.9\n", stdout());
        assertEquals("", stderr());

        reset();
        assertEquals(2, run(jar, "frobnicate"));
        assertTrue(stderr().contains("usage"), stderr());

        Path props = props(MINIMAL);
        String[][] malformed = {
            {"inject"},
            {"inject", props.toString(), props.toString()},
            {"inject", props.toString(), "--out"},
            {"inject", props.toString(), "--out", "a.jar", "--out", "b.jar"},
            {"inject", props.toString(), "--force"},
            {"--version", "extra"},
        };
        for (String[] args : malformed) {
            reset();
            assertEquals(2, run(jar, args), String.join(" ", args));
            assertTrue(stderr().contains("usage"), String.join(" ", args));
        }
        assertDirHolds(jar, props);
    }

    @Test
    void missingInputFileIsAnIoFailure() throws IOException {
        Path missing = dir.resolve("nope.properties");

        assertEquals(1, run(jar, "inject", missing.toString()));

        assertTrue(stderr().contains(missing.toString()), stderr());
        assertDirHolds(jar);
    }

    @Test
    void entryTimestampIsFixed() throws IOException {
        Path props = props(MINIMAL);
        assertEquals(0, run(jar, "inject", props.toString()));

        try (ZipFile zip = new ZipFile(Inject.defaultOutput(jar, "tag:mc-player").toFile())) {
            assertEquals(DOS_EPOCH, zip.getEntry(DeploymentSettings.RESOURCE).getTimeLocal());
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                assertEquals(DOS_EPOCH, e.getTimeLocal(), e.getName());
                assertNull(e.getExtra(), e.getName()); // no extended timestamp: independent of the time zone
            }
        }
    }

    @Test
    void refusesWhenNotRunFromAJar() throws IOException {
        Path props = props(MINIMAL);

        assertEquals(2, run(null, "inject", props.toString()));
        assertTrue(stderr().contains("inject must be run from the Cattail jar"), stderr());

        reset();
        assertEquals(2, run(dir, "inject", props.toString()));
        assertTrue(stderr().contains("inject must be run from the Cattail jar"), stderr());

        // Under Gradle the class comes from build/classes, not from a jar.
        reset();
        assertEquals(2, run(Inject.selfJar(), "inject", props.toString()));
        assertTrue(stderr().contains("inject must be run from the Cattail jar"), stderr());

        reset();
        assertEquals(2, run(dir, "--version"));
        assertTrue(stderr().contains("must be run from the Cattail jar"), stderr());
        assertDirHolds(jar, props);
    }

    @Test
    void versionNeedsTheManifestAttribute() throws IOException {
        Path bare = dir.resolve("bare.jar");
        try (JarOutputStream zip = new JarOutputStream(Files.newOutputStream(bare))) {
            put(zip, "a.txt", A_BYTES);
        }

        assertEquals(1, run(bare, "--version"));

        assertEquals("", stdout());
        assertTrue(stderr().contains("Implementation-Version"), stderr());
    }

    @Test
    void failedWriteLeavesNoPartialFile() throws IOException {
        Path props = props(MINIMAL);
        Path taken = dir.resolve("taken.jar");
        Files.createDirectories(taken.resolve("inside"));

        assertEquals(1, run(jar, "inject", props.toString(), "--out", taken.toString()));

        assertTrue(stderr().contains(taken.toString()), stderr());
        assertTrue(Files.isDirectory(taken.resolve("inside")));
        assertDirHolds(jar, props, taken);
    }

    @Test
    void outputWithoutAParentIsAWriteFailure() throws IOException {
        Path props = props(MINIMAL);
        Path root = dir.toAbsolutePath().getRoot(); // "/" on POSIX, the drive root on Windows

        assertEquals(1, run(jar, "inject", props.toString(), "--out", root.toString()));

        assertTrue(stderr().startsWith("cannot write " + root + ": "), stderr());
        assertTrue(stderr().contains("no parent directory"), stderr());
        assertEquals("", stdout());
        assertDirHolds(jar, props);
    }

    @Test
    void entryReadsBackAsTheSameSettings() throws IOException {
        // A backslash is the one character of a valid value that Properties.load would reinterpret.
        DeploymentSettings s = new DeploymentSettings("tskey-auth-EXAMPLE?a\\b", Optional.of(LocalDate.of(2026, 12, 31)),
                "tag:mc-player", "MYSPECS", Optional.of("100.101.102.103:22,100.101.102.103:25565"));

        byte[] entry = Inject.entryBytes(s);

        assertEquals("authkey=tskey-auth-EXAMPLE?a\\\\b\nauthkey.expires=2026-12-31\ntag=tag:mc-player\n"
                + "specs.prefix=MYSPECS\nprobe.targets=100.101.102.103:22,100.101.102.103:25565\n",
                new String(entry, UTF_8));
        assertEquals(s, DeploymentSettings.read(new ByteArrayInputStream(entry)));
    }

    // --- helpers ---

    private int run(Path selfJar, String... args) {
        return Inject.run(args, selfJar, out, err);
    }

    private String stdout() {
        return outBytes.toString(UTF_8);
    }

    private String stderr() {
        return errBytes.toString(UTF_8);
    }

    /** Clears both streams, after checking what they hold for the key like {@link #neverPrintsTheKey}. */
    private void reset() {
        neverPrintsTheKey();
        outBytes.reset();
        errBytes.reset();
    }

    private Path props(String text) throws IOException {
        Path p = dir.resolve("deployment.properties");
        Files.writeString(p, text, UTF_8);
        return p;
    }

    /**
     * A jar laid out like the built one: {@code META-INF/}, the manifest, {@code a.txt}, {@code dir/},
     * {@code dir/b.bin}, optionally an old deployment entry in the middle.
     */
    private Path fixture(String name, boolean probe, boolean legacyEntry) throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(Attributes.Name.IMPLEMENTATION_VERSION, "9.9.9");
        if (probe) {
            main.putValue(Inject.MARKER_ATTRIBUTE, "true");
        }
        Path path = dir.resolve(name);
        try (JarOutputStream zip = new JarOutputStream(Files.newOutputStream(path))) {
            put(zip, "META-INF/", new byte[0]);
            zip.putNextEntry(new ZipEntry(JarFile.MANIFEST_NAME));
            manifest.write(zip);
            zip.closeEntry();
            put(zip, "a.txt", A_BYTES);
            if (legacyEntry) {
                put(zip, DeploymentSettings.RESOURCE, "legacy=1\n".getBytes(UTF_8));
            }
            put(zip, "dir/", new byte[0]);
            put(zip, "dir/b.bin", B_BYTES);
        }
        return path;
    }

    private static void put(JarOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** Every entry of {@code jar} in order, read sequentially so a duplicate name fails the test. */
    private static Map<String, byte[]> entries(Path jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (InputStream file = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(file)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                assertNull(entries.put(e.getName(), zip.readAllBytes()), "duplicate entry " + e.getName());
            }
        }
        return entries;
    }

    /** The temp dir holds exactly these files: nothing partial, no temp file left behind. */
    private void assertDirHolds(Path... expected) throws IOException {
        Set<String> want = Stream.of(expected).map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        try (Stream<Path> listing = Files.list(dir)) {
            assertEquals(want, listing.map(p -> p.getFileName().toString()).collect(Collectors.toSet()));
        }
    }

    private static byte[] binary(int size) {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return bytes;
    }
}
