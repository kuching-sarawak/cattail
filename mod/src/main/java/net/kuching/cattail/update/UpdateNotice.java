package net.kuching.cattail.update;

import java.util.Optional;
import java.util.Set;

/**
 * Whether to tell the player that the server runs a newer Cattail, and what to say. Pure: no
 * Minecraft classes, so it is unit-tested; {@link UpdateClient} shows the result as a toast.
 */
public final class UpdateNotice {

    private UpdateNotice() {
    }

    /**
     * Compares two Cattail versions as dotted numbers ({@code 0.1.0}, {@code 0.10.2}): numeric
     * part by part, a missing part counting as 0. Anything from the first character that is not
     * a digit or a dot is ignored ({@code 0.1.0-dirty}, {@code 0.1.0+mc1.21.1}). A version that
     * does not start with a digit is never newer, so a {@code ?} from either side is harmless.
     */
    public static boolean isNewer(String candidate, String than) {
        int[] a = parts(candidate);
        int[] b = parts(than);
        if (a.length == 0 || b.length == 0) {
            return false;
        }
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] parts(String version) {
        int end = 0;
        while (end < version.length() && (Character.isDigit(version.charAt(end)) || version.charAt(end) == '.')) {
            end++;
        }
        if (end == 0 || !Character.isDigit(version.charAt(0))) {
            return new int[0];
        }
        String[] fields = version.substring(0, end).split("\\.");
        int[] out = new int[fields.length];
        for (int i = 0; i < fields.length; i++) {
            try {
                out[i] = fields[i].isEmpty() ? 0 : Integer.parseInt(fields[i]);
            } catch (NumberFormatException e) {
                out[i] = Integer.MAX_VALUE; // absurdly long numeric field: treat as very new
            }
        }
        return out;
    }

    /**
     * The notice to show, if any: only when {@code serverVersion} is newer than
     * {@code clientVersion} and {@code shown} does not yet hold {@code serverKey} (one notice per
     * server per game run; this adds the key). The text names both versions and the next step.
     */
    public static Optional<String> decide(String serverVersion, String clientVersion, String serverKey,
            Set<String> shown) {
        if (!isNewer(serverVersion, clientVersion)) {
            return Optional.empty();
        }
        if (!shown.add(serverKey)) {
            return Optional.empty();
        }
        return Optional.of(message(serverVersion, clientVersion));
    }

    /** The toast body: both versions and what to do. The title is {@link #TITLE}. */
    public static String message(String serverVersion, String clientVersion) {
        return "This server runs Cattail " + serverVersion + "; you have " + clientVersion
                + ". Ask the server owner for the new jar.";
    }

    public static final String TITLE = "Cattail update available";
}
