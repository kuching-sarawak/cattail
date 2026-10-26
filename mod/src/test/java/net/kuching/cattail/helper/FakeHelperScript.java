package net.kuching.cattail.helper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Copies the {@code fake-helper.sh} test resource to an executable temp file. */
final class FakeHelperScript {

    private FakeHelperScript() {
    }

    static Path copyTo(Path dir) throws IOException {
        Path script = dir.resolve("fake-helper.sh");
        try (InputStream in = FakeHelperScript.class.getResourceAsStream("/fake-helper.sh")) {
            if (in == null) {
                throw new IOException("fake-helper.sh missing from the test resources");
            }
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!script.toFile().setExecutable(true)) {
            throw new IOException("cannot make " + script + " executable");
        }
        return script;
    }
}
