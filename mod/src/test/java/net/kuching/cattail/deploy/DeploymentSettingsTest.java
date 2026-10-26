package net.kuching.cattail.deploy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeploymentSettingsTest {

    @Test
    void minimalFileParses() throws IOException {
        DeploymentSettings s = parse("authkey=tskey-auth-EXAMPLE\ntag=tag:mc-player\n");
        assertEquals("tskey-auth-EXAMPLE", s.authKey());
        assertEquals("tag:mc-player", s.tag());
        assertEquals("CATTAILSPECS", s.specsPrefix());
        assertTrue(s.authKeyExpires().isEmpty());
        assertTrue(s.probeTargets().isEmpty());
    }

    @Test
    void allKeysParseAndRoundTrip() throws IOException {
        String text = "authkey=tskey-client-EXAMPLE\nauthkey.expires=2026-12-31\ntag=tag:mc-player\n"
                + "specs.prefix=MYSPECS\nprobe.targets=100.101.102.103:22,100.101.102.103:25565\n";
        DeploymentSettings s = parse(text);
        assertEquals(LocalDate.of(2026, 12, 31), s.authKeyExpires().orElseThrow());
        assertEquals("MYSPECS", s.specsPrefix());
        assertEquals("100.101.102.103:22,100.101.102.103:25565", s.probeTargets().orElseThrow());
        assertEquals(Set.of("authkey", "authkey.expires", "tag", "specs.prefix", "probe.targets"),
                s.toProperties().stringPropertyNames());
        assertEquals(List.of("authkey", "authkey.expires", "tag", "specs.prefix", "probe.targets"),
                DeploymentSettings.KEYS);
    }

    @Test
    void valuesAreTrimmedAndBomAndCrlfAreTolerated() throws IOException {
        DeploymentSettings s = parse("\uFEFFauthkey = tskey-auth-EXAMPLE \r\ntag= tag:mc-player\r\n");
        assertEquals("tskey-auth-EXAMPLE", s.authKey());
        assertEquals("tag:mc-player", s.tag());
    }

    @Test
    void authKeyPrefixIsEnforced() {
        assertRule("authkey", "must start with tskey-client- or tskey-auth-", "authkey=secret\ntag=tag:a\n");
        assertRule("authkey", "must start with tskey-client- or tskey-auth-", "tag=tag:a\n"); // missing
        assertRule("authkey", "must be printable ASCII with no whitespace",
                "authkey=tskey-auth-EXAMPLE extra\ntag=tag:a\n");
    }

    @Test
    void authKeyMustBePrintableAsciiAfterThePrefix() {
        String rule = "must be printable ASCII with no whitespace";
        assertRule("authkey", rule, "authkey=tskey-auth-EXAMPLE\\u0000x\ntag=tag:a\n"); // NUL via a properties escape
        assertRule("authkey", rule, "authkey=tskey-auth-EXAMPLE\u00A0\ntag=tag:a\n"); // trailing NBSP, not trimmed
        assertRule("authkey", rule, "authkey=tskey-auth-\ntag=tag:a\n"); // bare prefix
        assertRule("authkey", rule, "authkey=tskey-client-\ntag=tag:a\n");
    }

    @Test
    void tagPatternIsEnforced() {
        assertRule("tag", "must match tag:[a-z0-9-]+", "authkey=tskey-auth-EXAMPLE\ntag=tag:MC-player\n");
        assertRule("tag", "must match tag:[a-z0-9-]+", "authkey=tskey-auth-EXAMPLE\n");
    }

    @Test
    void prefixIsValidatedNotDefaulted() {
        assertRule("specs.prefix", "must match ^[A-Z0-9_]{1,32}$",
                "authkey=tskey-auth-EXAMPLE\ntag=tag:a\nspecs.prefix=lower\n");
    }

    @Test
    void expiresMustBeIsoDate() {
        assertRule("authkey.expires", "must be a date YYYY-MM-DD",
                "authkey=tskey-auth-EXAMPLE\ntag=tag:a\nauthkey.expires=31/12/2026\n");
    }

    @Test
    void probeTargetsMustBeIpv4PortList() {
        assertRule("probe.targets", "must be a comma-separated list of ipv4:port",
                "authkey=tskey-auth-EXAMPLE\ntag=tag:a\nprobe.targets=example.com:22\n");
    }

    @Test
    void expiresRejectsImpossibleAndShortDates() {
        for (String date : new String[] {"2026-02-30", "2026-1-1", "+12026-12-31", "-0001-01-01", ""}) {
            assertRule("authkey.expires", "must be a date YYYY-MM-DD",
                    "authkey=tskey-auth-EXAMPLE\ntag=tag:a\nauthkey.expires=" + date + "\n");
        }
    }

    @Test
    void probeTargetsRejectOutOfRangeMalformedAndEmptyEntries() throws IOException {
        for (String targets : new String[] {"192.0.2.256:22", "192.0.2.1:0", "192.0.2.1:65536", "192.0.2.1:022",
                "192.0.2.01:22", "192.0.2.1", "192.0.2.1:22,", ",192.0.2.1:22", "192.0.2.1:22, 192.0.2.2:22", ""}) {
            assertRule("probe.targets", "must be a comma-separated list of ipv4:port",
                    "authkey=tskey-auth-EXAMPLE\ntag=tag:a\nprobe.targets=" + targets + "\n");
        }
        assertEquals("0.0.0.0:1,255.255.255.255:65535",
                parse("authkey=tskey-auth-EXAMPLE\ntag=tag:a\nprobe.targets=0.0.0.0:1,255.255.255.255:65535\n")
                        .probeTargets().orElseThrow());
    }

    @Test
    void presentButEmptyPrefixIsRejected() {
        assertRule("specs.prefix", "must match ^[A-Z0-9_]{1,32}$",
                "authkey=tskey-auth-EXAMPLE\ntag=tag:a\nspecs.prefix=\n");
    }

    @Test
    void toPropertiesHasOnlyThePresentKeys() throws IOException {
        DeploymentSettings s = parse("authkey=tskey-auth-EXAMPLE \ntag= tag:mc-player\n");
        assertEquals(Set.of("authkey", "tag"), s.toProperties().stringPropertyNames());
        assertEquals("tskey-auth-EXAMPLE", s.toProperties().getProperty("authkey"));
        assertEquals("tag:mc-player", s.toProperties().getProperty("tag"));
    }

    @Test
    void unknownKeyIsAnError() {
        assertRule("authKey", "is not a known key",
                "authKey=tskey-auth-EXAMPLE\nauthkey=tskey-auth-EXAMPLE\ntag=tag:a\n");
    }

    @Test
    void unknownKeysAreEchoedOnlyWhenTheyLookLikeKeys() {
        String rule = "has no key=value form or is not a known key";
        String valid = "authkey=tskey-auth-EXAMPLE\ntag=tag:a\n";
        assertRule("specs-prefix", "is not a known key", valid + "specs-prefix=X\n");
        assertRule("line", rule, valid + "k".repeat(33) + "=1\n"); // longer than 32
        assertRule("line", rule, valid + "bad!key=1\n"); // outside [A-Za-z0-9._-]
        assertRule("line", rule, valid + "tskeyring=1\n"); // starts with tskey
        assertRule("line", rule, valid + "loose\n"); // no key=value form
        assertRule("line", rule, valid + "loose=\n");
    }

    @Test
    void messagesNeverContainValues() {
        DeploymentException e = assertThrows(DeploymentException.class,
                () -> parse("authkey=SENTINEL-not-a-key\ntag=tag:a\n"));
        assertFalse(e.getMessage().contains("SENTINEL"));
        assertFalse(e.toString().contains("SENTINEL"));

        // A secret pasted on a line of its own becomes a key with an empty value.
        assertNoExample("tskey-client-EXAMPLE\ntag=tag:a\n");
        // The second half of a key that wrapped onto the next line.
        assertNoExample("authkey=tskey-auth-EXAMPLE\nEXAMPLESECOND\ntag=tag:a\n");
    }

    @Test
    void authKeyForHelperAppendsOptionsOnlyToBareClientSecrets() throws IOException {
        assertEquals("tskey-client-EXAMPLE?ephemeral=true&preauthorized=true",
                with("tskey-client-EXAMPLE").authKeyForHelper());
        assertEquals("tskey-client-EXAMPLE?ephemeral=false",
                with("tskey-client-EXAMPLE?ephemeral=false").authKeyForHelper());
        assertEquals("tskey-auth-EXAMPLE", with("tskey-auth-EXAMPLE").authKeyForHelper());
    }

    @Test
    void expiryIsInclusiveOfTheLastDay() throws IOException {
        DeploymentSettings s = parse("authkey=tskey-auth-EXAMPLE\ntag=tag:a\nauthkey.expires=2026-10-21\n");
        assertFalse(s.expired(LocalDate.of(2026, 10, 21)));
        assertTrue(s.expired(LocalDate.of(2026, 10, 22)));
        assertEquals(OptionalLong.of(7), s.daysUntilExpiry(LocalDate.of(2026, 10, 14)));
        assertEquals(OptionalLong.empty(), with("tskey-auth-EXAMPLE").daysUntilExpiry(LocalDate.of(2026, 10, 14)));
    }

    @Test
    void toStringRedactsTheKey() throws IOException {
        assertFalse(with("tskey-auth-EXAMPLE").toString().contains("EXAMPLE"));
    }

    @Test
    void malformedEscapeIsAReadFailureNotAnUncheckedException() {
        IOException e = assertThrows(IOException.class, () -> parse("authkey=\\uZZZZ\ntag=tag:a\n"));
        assertFalse(e instanceof DeploymentException);
    }

    /** {@link DeploymentSettings#read} of {@code text} encoded as UTF-8. */
    private static DeploymentSettings parse(String text) throws IOException {
        return DeploymentSettings.read(new ByteArrayInputStream(text.getBytes(UTF_8)));
    }

    /** A minimal valid file with the given {@code authkey}. */
    private static DeploymentSettings with(String key) throws IOException {
        return parse("authkey=" + key + "\ntag=tag:a\n");
    }

    /** {@code text} is rejected, and nothing derived from the rejection contains {@code EXAMPLE}. */
    private static void assertNoExample(String text) {
        DeploymentException e = assertThrows(DeploymentException.class, () -> parse(text));
        assertEquals("line", e.key());
        assertFalse(e.getMessage().contains("EXAMPLE"), e.getMessage());
        assertFalse(e.toString().contains("EXAMPLE"));
        assertFalse(new Deployment.Invalid(e.key()).toString().contains("EXAMPLE"));
    }

    /** {@code text} is rejected with {@code key} as the key and {@code key + " " + rule} as the message. */
    private static void assertRule(String key, String rule, String text) {
        DeploymentException e = assertThrows(DeploymentException.class, () -> parse(text));
        assertEquals(key, e.key());
        assertEquals(key + " " + rule, e.getMessage());
    }
}
