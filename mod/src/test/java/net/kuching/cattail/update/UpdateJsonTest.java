package net.kuching.cattail.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * update.json at the repository root feeds NeoForge's update checker through updateJSONURL. A
 * release that forgets to bump it would tell every player the previous version is the latest,
 * so the promos must name this build's version, and the manifest must point at that file.
 */
class UpdateJsonTest {

    private static final Pattern VERSION = Pattern.compile("(?m)^version=\"([^\"]+)\"$");
    private static final Pattern MC_VERSION = Pattern.compile("(?m)^versionRange=\"\\[([0-9.]+)\\]\"$");
    private static final Pattern UPDATE_URL = Pattern.compile("(?m)^updateJSONURL=\"([^\"]+)\"$");

    @Test
    void promosNameThisVersionAndTheManifestPointsAtTheFile() throws IOException {
        String toml = cattailModsToml();
        String version = group(VERSION, toml, "version");
        String mc = group(MC_VERSION, toml, "the Minecraft version range");
        String url = group(UPDATE_URL, toml, "updateJSONURL");
        assertTrue(url.endsWith("/update.json"), "updateJSONURL must point at update.json: " + url);

        JsonObject json = JsonParser.parseString(resourceText("repo/update.json")).getAsJsonObject();
        JsonObject promos = json.getAsJsonObject("promos");
        assertNotNull(promos, "update.json has no promos");
        assertEquals(version, promos.get(mc + "-latest").getAsString(), mc + "-latest");
        assertEquals(version, promos.get(mc + "-recommended").getAsString(), mc + "-recommended");
        JsonObject changelog = json.getAsJsonObject(mc);
        assertNotNull(changelog, "update.json has no \"" + mc + "\" changelog section");
        assertTrue(changelog.has(version), "the \"" + mc + "\" section has no entry for " + version);
    }

    private static String group(Pattern p, String text, String what) {
        Matcher m = p.matcher(text);
        assertTrue(m.find(), "neoforge.mods.toml declares no " + what);
        return m.group(1);
    }

    private static String cattailModsToml() throws IOException {
        ClassLoader loader = UpdateJsonTest.class.getClassLoader();
        for (URL url : Collections.list(loader.getResources("META-INF/neoforge.mods.toml"))) {
            try (InputStream in = url.openStream()) {
                String toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (toml.contains("modId=\"cattail\"")) {
                    return toml;
                }
            }
        }
        throw new AssertionError("Cattail's neoforge.mods.toml is not on the test classpath");
    }

    private static String resourceText(String resource) throws IOException {
        try (InputStream in = UpdateJsonTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is not on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
