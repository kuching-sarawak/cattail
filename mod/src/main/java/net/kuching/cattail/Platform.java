package net.kuching.cattail;

import java.nio.file.Path;

import net.neoforged.fml.loading.FMLPaths;

/** Host platform queries used to decide whether the bundled helper can run. */
public final class Platform {

    private Platform() {
    }

    /** True when this JVM runs on 64-bit x86 Windows, the only platform the helper ships for. */
    public static boolean isWindowsAmd64() {
        return isWindowsAmd64(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static boolean isWindowsAmd64(String osName, String osArch) {
        return osName != null && osName.startsWith("Windows") && "amd64".equals(osArch);
    }

    /** The Minecraft game (instance) directory. */
    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }
}
