package net.kuching.cattail.deploy;

import java.io.IOException;
import java.io.InputStream;
import org.slf4j.LoggerFactory;

/**
 * What the jar's {@value DeploymentSettings#RESOURCE} says, as one of three states that cannot be
 * confused: {@link Generic} (no file: a generic jar nobody has injected), {@link Invalid} (a file
 * that was rejected; only the key is kept, never a value) or {@link Configured}.
 */
public sealed interface Deployment permits Deployment.Generic, Deployment.Invalid, Deployment.Configured {

    /** The resource is absent. */
    record Generic() implements Deployment {}

    /**
     * The resource is present but rejected. {@code key} names the offending key, or is {@code line}
     * for a line that cannot be named safely, or {@code file} when the file could not be read.
     */
    record Invalid(String key) implements Deployment {}

    /** The resource is present and valid. */
    record Configured(DeploymentSettings settings) implements Deployment {}

    /**
     * Reads {@value DeploymentSettings#RESOURCE} through {@code loader}. A rejected file is logged
     * once at ERROR with its key and rule, never a value.
     */
    static Deployment load(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(DeploymentSettings.RESOURCE)) {
            if (in == null) {
                return new Generic();
            }
            return new Configured(DeploymentSettings.read(in));
        } catch (DeploymentException e) {
            // The message is "<key> <rule>" and never holds a value.
            LoggerFactory.getLogger(Deployment.class).error("Cattail deployment file rejected: {}", e.getMessage());
            return new Invalid(e.key());
        } catch (IOException e) {
            LoggerFactory.getLogger(Deployment.class)
                    .error("Cattail deployment file rejected: file could not be read ({})", e.toString());
            return new Invalid("file");
        }
    }

    /** {@link #load} through the mod's own class loader, read once and cached. */
    static Deployment bundled() {
        return BundledDeployment.VALUE;
    }
}
