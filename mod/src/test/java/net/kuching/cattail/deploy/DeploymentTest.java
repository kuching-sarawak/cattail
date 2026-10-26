package net.kuching.cattail.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;

class DeploymentTest {

    @Test
    void loadReturnsGenericWhenAbsent() throws IOException {
        try (URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
            assertEquals(new Deployment.Generic(), Deployment.load(loader));
        }
    }

    @Test
    void loadReturnsConfiguredFromResource() throws IOException {
        try (URLClassLoader loader = loaderAt("/deployment-ok/")) {
            Deployment.Configured configured = assertInstanceOf(Deployment.Configured.class, Deployment.load(loader));
            assertEquals("tag:example", configured.settings().tag());
        }
    }

    @Test
    void loadReturnsInvalidWithKeyOnly() throws IOException {
        try (URLClassLoader loader = loaderAt("/deployment-bad/")) {
            assertEquals(new Deployment.Invalid("tag"), Deployment.load(loader));
        }
    }

    @Test
    void loadReturnsInvalidFileWhenTheFileCannotBeRead() throws IOException {
        try (URLClassLoader loader = loaderAt("/deployment-malformed/")) {
            assertEquals(new Deployment.Invalid("file"), Deployment.load(loader));
        }
    }

    /** A class loader that sees only the test resource directory {@code dir} (no parent, no main resources). */
    private static URLClassLoader loaderAt(String dir) {
        URL root = DeploymentTest.class.getResource(dir);
        assertNotNull(root, dir);
        return new URLClassLoader(new URL[] {root}, null);
    }
}
