package net.kuching.cattail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * The logo on the Mods screen. NeoForge reads {@code logoFile} from the jar root and draws it
 * 50 GUI pixels tall (ModListScreen); a missing or misnamed file just shows no logo, so nothing
 * else would notice. The README shows the same logo, scaled up because GitHub strips the inline
 * style that would keep a small pixel-art image sharp.
 */
class ModLogoTest {

    /** NeoForge's documented character set for logoFile. */
    private static final Pattern LOGO_FILE = Pattern.compile("(?m)^logoFile=\"([a-z0-9_./-]+)\"$");
    private static final Pattern LOGO_BLUR = Pattern.compile("(?m)^logoBlur=(true|false)$");
    /** Height of the logo on the Mods screen, in GUI pixels. */
    private static final int LOGO_GUI_PX = 50;
    /** Largest automatic GUI scale on a 1920x1080 window. */
    private static final int GUI_SCALE_1080P = 4;
    /** README.md and .github/, copied onto the test classpath by processTestResources. */
    private static final String REPO = "repo/";
    private static final Pattern README_IMG = Pattern.compile("<img src=\"([^\"]+)\"[^>]*\\swidth=\"(\\d+)\"");

    @Test
    void logoIsDeclaredAndIsASquareImageAtTheJarRoot() throws IOException {
        BufferedImage logo = readLogo(declaredLogo(cattailModsToml()));
        assertEquals(logo.getWidth(), logo.getHeight(), "the Mods screen draws the logo as a square");
    }

    @Test
    void logoResolutionMatchesItsFiltering() throws IOException {
        String toml = cattailModsToml();
        BufferedImage logo = readLogo(declaredLogo(toml));
        Matcher blur = LOGO_BLUR.matcher(toml);
        boolean linear = !blur.find() || Boolean.parseBoolean(blur.group(1));
        if (linear) {
            // Linear filtering without mipmaps: at least one texel per screen pixel up to 1080p.
            assertTrue(logo.getWidth() >= LOGO_GUI_PX * GUI_SCALE_1080P,
                    "a smoothly filtered logo needs at least " + LOGO_GUI_PX * GUI_SCALE_1080P + " px, got "
                            + logo.getWidth());
        } else {
            // Nearest filtering: one texel per GUI pixel keeps every texel square at any GUI scale.
            assertEquals(LOGO_GUI_PX, logo.getWidth(), "a pixel-art logo (logoBlur=false) must be 50 px");
        }
    }

    @Test
    void readmeShowsTheJarLogoScaledUpPixelForPixel() throws IOException {
        BufferedImage logo = readLogo(declaredLogo(cattailModsToml()));
        Matcher img = README_IMG.matcher(resourceText(REPO + "README.md"));
        assertTrue(img.find(), "README.md shows no logo with a width");
        BufferedImage copy = readImage(REPO + img.group(1), img.group(1) + " (shown in README.md) is not in the repository");
        int scale = copy.getWidth() / logo.getWidth();
        assertTrue(scale >= 1 && copy.getWidth() == logo.getWidth() * scale && copy.getHeight() == logo.getHeight() * scale,
                "the README logo must be the jar logo scaled by a whole number, got " + copy.getWidth() + "x"
                        + copy.getHeight() + " for " + logo.getWidth() + "x" + logo.getHeight());
        for (int y = 0; y < copy.getHeight(); y++) {
            for (int x = 0; x < copy.getWidth(); x++) {
                int want = logo.getRGB(x / scale, y / scale);
                int got = copy.getRGB(x, y);
                // Fully transparent pixels may carry any colour.
                if ((want >>> 24) != 0 || (got >>> 24) != 0) {
                    assertEquals(want, got, "README logo pixel (" + x + ", " + y + ") differs from the jar logo");
                }
            }
        }
        // A whole number of CSS pixels per texel, so browsers draw the README logo sharp.
        int width = Integer.parseInt(img.group(2));
        assertEquals(0, width % logo.getWidth(), "README logo width " + width + " is not a multiple of "
                + logo.getWidth());
    }

    private static String declaredLogo(String toml) {
        Matcher m = LOGO_FILE.matcher(toml);
        assertTrue(m.find(), "neoforge.mods.toml declares no logoFile");
        return m.group(1);
    }

    private static String cattailModsToml() throws IOException {
        // NeoForge's own jar is on the test classpath with its own neoforge.mods.toml.
        ClassLoader loader = ModLogoTest.class.getClassLoader();
        for (URL url : Collections.list(loader.getResources("META-INF/neoforge.mods.toml"))) {
            try (InputStream in = url.openStream()) {
                String toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (toml.contains("modId=\"cattail\"")) {
                    return toml;
                }
            }
        }
        return fail("Cattail's neoforge.mods.toml is not on the test classpath");
    }

    private static BufferedImage readLogo(String logoFile) throws IOException {
        return readImage(logoFile, logoFile + " is not at the jar root");
    }

    private static BufferedImage readImage(String resource, String missing) throws IOException {
        URL url = ModLogoTest.class.getClassLoader().getResource(resource);
        assertNotNull(url, missing);
        BufferedImage image = ImageIO.read(url);
        assertNotNull(image, resource + " is not a readable image");
        return image;
    }

    private static String resourceText(String resource) throws IOException {
        try (InputStream in = ModLogoTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is not on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
