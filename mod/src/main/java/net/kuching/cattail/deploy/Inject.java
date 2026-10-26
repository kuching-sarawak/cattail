package net.kuching.cattail.deploy;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.CodeSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The jar's {@code Main-Class}, the owner's command:
 * {@code java -jar cattail-<ver>.jar inject <deployment.properties> [--out <file.jar>]} validates
 * the deployment file and writes a copy of this jar that carries it as
 * {@value DeploymentSettings#RESOURCE}; {@code --version} prints the jar's
 * {@code Implementation-Version}.
 *
 * <p>Uses only the JDK and this package, so it runs on a bare JRE with only the mod jar on the
 * class path. Never prints the key or the file's contents: messages carry paths, keys and rules.
 * Exit codes: {@value #OK} success, {@value #USAGE} usage or validation, {@value #IO_FAILURE} I/O
 * failure.
 */
public final class Inject {

    /** Manifest attribute, {@code true} on a probe build: only such a jar accepts {@code probe.targets}. */
    public static final String MARKER_ATTRIBUTE = "Cattail-Probe";

    static final int OK = 0;
    static final int IO_FAILURE = 1;
    static final int USAGE = 2;

    /** Every entry's time: the DOS epoch Gradle writes under {@code preserveFileTimestamps = false}. */
    static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

    private static final String NOT_FROM_JAR = "inject must be run from the Cattail jar";
    private static final String TAG_PREFIX = "tag:";
    private static final String JAR_SUFFIX = ".jar";

    private Inject() {}

    public static void main(String[] args) {
        System.exit(run(args, selfJar(), System.out, System.err));
    }

    /**
     * The command, with its own jar, {@code selfJar} ({@code null} when not run from a file), as
     * both the input and the manifest that says the version and whether it is a probe build.
     *
     * @return the exit code
     */
    static int run(String[] args, Path selfJar, PrintStream out, PrintStream err) {
        if (args.length == 1 && args[0].equals("--version")) {
            return version(selfJar, out, err);
        }
        if (args.length == 0 || !args[0].equals("inject")) {
            return usage(selfJar, err);
        }
        String fileArg = null;
        String outArg = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--out") && outArg == null && i + 1 < args.length) {
                outArg = args[++i];
            } else if (!args[i].startsWith("-") && fileArg == null) {
                fileArg = args[i];
            } else {
                return usage(selfJar, err);
            }
        }
        if (fileArg == null) {
            return usage(selfJar, err);
        }
        if (!isJar(selfJar)) {
            return fail(err, USAGE, NOT_FROM_JAR);
        }
        Path file;
        Path explicitOut;
        try {
            file = Path.of(fileArg);
            explicitOut = outArg == null ? null : Path.of(outArg);
        } catch (InvalidPathException e) {
            return fail(err, USAGE, e.getMessage());
        }

        // Read first, then parse from memory: exit 1 means the file could not be read, exit 2
        // that its content was rejected.
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (IOException e) {
            return fail(err, IO_FAILURE, "cannot read " + file + ": " + reason(e));
        }
        DeploymentSettings settings;
        try {
            settings = DeploymentSettings.read(new ByteArrayInputStream(content));
        } catch (IOException e) {
            // A DeploymentException message is "<key> <rule>"; the other is fixed text. No value.
            return fail(err, USAGE, file + ": " + e.getMessage());
        }
        if (settings.expired(LocalDate.now())) {
            return fail(err, USAGE, file + ": " + DeploymentSettings.AUTHKEY_EXPIRES + " is in the past");
        }

        Attributes manifest;
        try {
            manifest = mainAttributes(selfJar);
        } catch (IOException e) {
            return fail(err, IO_FAILURE, "cannot read " + selfJar + ": " + reason(e));
        }
        if (settings.probeTargets().isPresent() && !"true".equals(manifest.getValue(MARKER_ATTRIBUTE))) {
            return fail(err, USAGE, file + ": " + DeploymentSettings.PROBE_TARGETS + " is only allowed in a probe jar");
        }

        Path output = explicitOut != null ? explicitOut : defaultOutput(selfJar, settings.tag());
        try {
            if (Files.exists(output)) {
                if (Files.isSameFile(output, selfJar)) {
                    return fail(err, USAGE, output + " is the input jar; inject never writes in place, give another --out");
                }
                if (Files.isSameFile(output, file)) {
                    return fail(err, USAGE, output + " is the deployment file; give another --out");
                }
            }
        } catch (IOException e) {
            return fail(err, IO_FAILURE, "cannot check " + output + ": " + reason(e));
        }
        if (explicitOut == null && Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            return fail(err, USAGE, output + " already exists; give --out " + output + " to replace it");
        }

        try {
            writeInjected(selfJar, output, settings);
        } catch (IOException e) {
            return fail(err, IO_FAILURE, "cannot write " + output + ": " + reason(e));
        }
        out.print("Wrote " + output.toAbsolutePath().normalize() + " for " + settings.tag() + "\n");
        return OK;
    }

    /** {@code <dir>/<input name without .jar>-<tag without "tag:">.jar}, next to {@code input}. */
    static Path defaultOutput(Path input, String tag) {
        String name = input.getFileName().toString();
        String stem = isJarName(name) ? name.substring(0, name.length() - JAR_SUFFIX.length()) : name;
        String tagName = tag.startsWith(TAG_PREFIX) ? tag.substring(TAG_PREFIX.length()) : tag;
        return input.resolveSibling(stem + "-" + tagName + JAR_SUFFIX);
    }

    /**
     * Writes a copy of the jar {@code input} to {@code output}: every entry except an existing
     * {@value DeploymentSettings#RESOURCE}, in the input's order with identical content, then that
     * entry from {@link #entryBytes}. Every entry is DEFLATED, has time {@link #ENTRY_TIME} and no
     * extra field or comment, so equal input gives a byte-identical output. The jar holds the key,
     * so where the file store has POSIX permissions it is readable and writable by the owner only,
     * from creation on. It is written to a temporary file next to {@code output} and moved over it
     * atomically: a failure leaves neither a partial output nor the temporary file, an existing
     * output is replaced by a new file (permissions included), and {@code input} is only read. An
     * output with no parent directory (a file system root) fails before anything is created.
     */
    static void writeInjected(Path input, Path output, DeploymentSettings s) throws IOException {
        Path target = output.toAbsolutePath();
        if (target.getParent() == null) {
            // No file name and no folder for the temporary file: resolveSibling would return a
            // relative ".null.<uuid>.tmp" with no parent of its own.
            throw new FileSystemException(target.toString(), null, "no parent directory");
        }
        Path temp = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        OutputStream file = createOwnerOnly(temp); // nothing to clean up if this throws
        try {
            try (file; ZipFile zip = new ZipFile(input.toFile()); ZipOutputStream jar = new ZipOutputStream(file)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().equals(DeploymentSettings.RESOURCE)) {
                        continue;
                    }
                    jar.putNextEntry(newEntry(entry.getName()));
                    try (InputStream in = zip.getInputStream(entry)) {
                        in.transferTo(jar);
                    }
                    jar.closeEntry();
                }
                jar.putNextEntry(newEntry(DeploymentSettings.RESOURCE));
                jar.write(entryBytes(s));
                jar.closeEntry();
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                t.addSuppressed(cleanup);
            }
            throw t;
        }
    }

    /**
     * The deployment entry: {@code key=value\n} for each key {@link DeploymentSettings#toProperties}
     * holds, in {@link DeploymentSettings#KEYS} order, UTF-8, no comment or date line. A backslash,
     * the only character of a valid value that {@link Properties#load} reinterprets, is doubled so
     * the entry reads back as the same settings.
     */
    static byte[] entryBytes(DeploymentSettings s) {
        Properties p = s.toProperties();
        StringBuilder text = new StringBuilder();
        for (String key : DeploymentSettings.KEYS) {
            String value = p.getProperty(key);
            if (value != null) {
                text.append(key).append('=').append(value.replace("\\", "\\\\")).append('\n');
            }
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The file this class was loaded from, by its code source; {@code null} when that is not a local path. */
    static Path selfJar() {
        CodeSource source = Inject.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        try {
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
            return null;
        }
    }

    private static int version(Path selfJar, PrintStream out, PrintStream err) {
        if (!isJar(selfJar)) {
            return fail(err, USAGE, NOT_FROM_JAR);
        }
        String version;
        try {
            version = mainAttributes(selfJar).getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        } catch (IOException e) {
            return fail(err, IO_FAILURE, "cannot read " + selfJar + ": " + reason(e));
        }
        if (version == null) {
            return fail(err, IO_FAILURE, selfJar + " has no Implementation-Version in its manifest");
        }
        out.print(version + "\n");
        return OK;
    }

    private static int usage(Path selfJar, PrintStream err) {
        String jar = isJar(selfJar) ? selfJar.getFileName().toString() : "cattail.jar";
        String stem = jar.substring(0, jar.length() - JAR_SUFFIX.length());
        err.print("usage: java -jar " + jar + " inject <deployment.properties> [--out <file.jar>]\n"
                + "       java -jar " + jar + " --version\n"
                + "inject writes a copy of this jar that carries the deployment file, next to it as\n"
                + stem + "-<tag>.jar unless --out names the file.\n");
        return USAGE;
    }

    private static int fail(PrintStream err, int code, String message) {
        err.print(message + "\n");
        return code;
    }

    /** The exception's type and message: a path or an OS reason, never file content. */
    private static String reason(IOException e) {
        String type = e.getClass().getSimpleName();
        return e.getMessage() == null ? type : type + ": " + e.getMessage();
    }

    private static boolean isJar(Path p) {
        return p != null && p.getFileName() != null && isJarName(p.getFileName().toString()) && Files.isRegularFile(p);
    }

    private static boolean isJarName(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(JAR_SUFFIX);
    }

    private static Attributes mainAttributes(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            return manifest == null ? new Attributes() : manifest.getMainAttributes();
        }
    }

    /**
     * Creates {@code path}, which has a parent directory, and opens it for writing in one call
     * ({@code CREATE_NEW}, so an existing file is never opened), owner read and write only when its
     * file store supports POSIX permissions; elsewhere (Windows) with the file store's defaults.
     */
    private static OutputStream createOwnerOnly(Path path) throws IOException {
        Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (Files.getFileStore(path.getParent()).supportsFileAttributeView(PosixFileAttributeView.class)) {
            return Channels.newOutputStream(Files.newByteChannel(path, options,
                    PosixFilePermissions.asFileAttribute(EnumSet.of(OWNER_READ, OWNER_WRITE))));
        }
        return Channels.newOutputStream(Files.newByteChannel(path, options));
    }

    private static ZipEntry newEntry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(ENTRY_TIME);
        return entry;
    }
}
