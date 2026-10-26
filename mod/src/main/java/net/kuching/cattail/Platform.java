package net.kuching.cattail;

import java.nio.file.Path;

import net.kuching.cattail.helper.HelperTarget;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Host platform queries: whether the JVM's OS and processor have a helper target (decided by
 * {@link HelperTarget}, never here) and where the game directory is.
 */
public final class Platform {

    private Platform() {
    }

    /**
     * True when this JVM's OS and processor are one of the {@link HelperTarget}s, that is when
     * {@link HelperTarget#current()} is present.
     */
    public static boolean helperSupported() {
        return HelperTarget.current().isPresent();
    }

    /** The Minecraft game (instance) directory. */
    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }
}
