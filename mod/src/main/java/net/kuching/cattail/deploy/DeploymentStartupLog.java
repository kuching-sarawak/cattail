package net.kuching.cattail.deploy;

import java.time.LocalDate;
import java.util.OptionalLong;
import org.slf4j.Logger;

/**
 * The dedicated server's start-up line about the jar's deployment, so the owner reads in the
 * server log what breaks or is about to break joining: a generic jar, a rejected deployment file,
 * or an {@code authkey.expires} within {@value #WARN_DAYS} days or already past. Logs one line at
 * most, and of the file's values only that date.
 */
public final class DeploymentStartupLog {

    /** A configured key expiring this many days from today, or fewer, gets a WARN. */
    static final long WARN_DAYS = 7;

    private DeploymentStartupLog() {}

    /**
     * Logs what {@code d} means for players joining today: WARN for a generic jar, ERROR naming
     * the key of a rejected file, WARN with the day count when the key expires within
     * {@value #WARN_DAYS} days (0 on its last valid day), ERROR with the date once it has passed,
     * and nothing otherwise (no expiry date, or a later one).
     */
    public static void log(Deployment d, LocalDate today, Logger log) {
        switch (d) {
            case Deployment.Generic() ->
                    log.warn("Cattail: this jar has not been set up by a server owner (no deployment file)");
            case Deployment.Invalid(String key) -> log.error("Cattail: deployment file rejected ({})", key);
            case Deployment.Configured(DeploymentSettings s) -> logExpiry(s, today, log);
        }
    }

    private static void logExpiry(DeploymentSettings s, LocalDate today, Logger log) {
        if (s.expired(today)) {
            log.error("Cattail: the server key expired on {}; players cannot join until a new jar is injected",
                    s.authKeyExpires().orElseThrow());
            return;
        }
        OptionalLong days = s.daysUntilExpiry(today); // not expired, so 0 or more when present
        if (days.isPresent() && days.getAsLong() <= WARN_DAYS) {
            log.warn("Cattail: the server key expires in {} day(s); inject a new deployment.properties",
                    days.getAsLong());
        }
    }
}
