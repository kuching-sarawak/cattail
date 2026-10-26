package net.kuching.cattail.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kuching.cattail.RecordingLogger;
import org.junit.jupiter.api.Test;

class DeploymentStartupLogTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final String EXPIRES_SOON =
            "warn|Cattail: the server key expires in {} day(s); inject a new deployment.properties|";

    private static List<String> logged(Deployment d) {
        List<String> out = new ArrayList<>();
        DeploymentStartupLog.log(d, TODAY, RecordingLogger.into(out));
        return out;
    }

    private static Deployment expiringOn(Optional<LocalDate> expires) {
        return new Deployment.Configured(new DeploymentSettings("tskey-auth-EXAMPLE", expires, "tag:example",
                DeploymentSettings.DEFAULT_PREFIX, Optional.empty()));
    }

    @Test
    void genericJarWarnsThatItIsNotSetUp() {
        assertEquals(List.of("warn|Cattail: this jar has not been set up by a server owner (no deployment file)"),
                logged(new Deployment.Generic()));
    }

    @Test
    void invalidFileIsAnErrorNamingTheKey() {
        assertEquals(List.of("error|Cattail: deployment file rejected ({})|tag"),
                logged(new Deployment.Invalid("tag")));
    }

    @Test
    void expiringWithinSevenDaysIsAWarningWithTheDayCount() {
        assertEquals(List.of(EXPIRES_SOON + "7"), logged(expiringOn(Optional.of(TODAY.plusDays(7)))));
        assertEquals(List.of(EXPIRES_SOON + "1"), logged(expiringOn(Optional.of(TODAY.plusDays(1)))));
    }

    @Test
    void expiresTodayIsAWarningNotAnError() {
        assertEquals(List.of(EXPIRES_SOON + "0"), logged(expiringOn(Optional.of(TODAY))));
    }

    @Test
    void expiredKeyIsAnErrorWithTheDate() {
        assertEquals(List.of("error|Cattail: the server key expired on {}; players cannot join until a new jar"
                + " is injected|2026-10-20"), logged(expiringOn(Optional.of(TODAY.minusDays(1)))));
    }

    @Test
    void nothingLoggedEightDaysOut() {
        assertEquals(List.of(), logged(expiringOn(Optional.of(TODAY.plusDays(8)))));
    }

    @Test
    void nothingLoggedWithoutAnExpiryDate() {
        assertEquals(List.of(), logged(expiringOn(Optional.empty())));
    }
}
