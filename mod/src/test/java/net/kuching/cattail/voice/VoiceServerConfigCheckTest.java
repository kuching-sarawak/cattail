package net.kuching.cattail.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.kuching.cattail.voice.VoiceServerConfigCheck.Finding;
import net.kuching.cattail.voice.VoiceServerConfigCheck.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceServerConfigCheckTest {

    private static final String DEFAULTS = """
            [host]
            ip = "0.0.0.0"
            port = 0
            """;

    @Test
    void defaultsAreClean() {
        assertEquals(List.of(), VoiceServerConfigCheck.inspect(DEFAULTS, 25565));
    }

    @Test
    void explicitGamePortIsClean() {
        assertEquals(List.of(), VoiceServerConfigCheck.inspect("[host]\nip = \"0.0.0.0\"\nport = 25565\n", 25565));
    }

    @Test
    void movedPortWarns() {
        List<Finding> f = VoiceServerConfigCheck.inspect("[host]\nport = 24454\n", 25565);
        assertEquals(1, f.size());
        assertEquals(Severity.WARN, f.get(0).severity());
        assertEquals("Cattail voice: Plasmo Voice [host] port is 24454 but the game port is 25565; tailnet players send voice "
                + "to the game port. Set port = 0 in config/plasmovoice/server/config.toml, or publish voice=24454 in the "
                + "TXT record and grant udp:24454.", f.get(0).message());
    }

    @Test
    void publicIpWarns() {
        List<Finding> f = VoiceServerConfigCheck.inspect(DEFAULTS + "[host.public]\nip = \"127.0.0.1\"\nport = 0\n", 25565);
        assertEquals(List.of(new Finding(Severity.WARN, "Cattail voice: Plasmo Voice [host.public] ip = \"127.0.0.1\" breaks "
                + "tailnet and LAN players. Set ip = \"0.0.0.0\" (clients then keep the address they joined with) or remove "
                + "[host.public].")), f);
    }

    @Test
    void publicWildcardWithOtherPortIsInfo() {
        List<Finding> f = VoiceServerConfigCheck.inspect(DEFAULTS + "[host.public]\nip = \"0.0.0.0\"\nport = 25570\n", 25565);
        assertEquals(List.of(new Finding(Severity.INFO, "Cattail voice: Plasmo Voice [host.public] port 25570 differs from the "
                + "game port 25565; make sure the TXT record has voice=25570 and the policy grants udp:25570.")), f);
    }

    @Test
    void publicWildcardWithZeroOrGamePortIsClean() {
        assertEquals(List.of(), VoiceServerConfigCheck.inspect(DEFAULTS + "[host.public]\nip = \"0.0.0.0\"\nport = 0\n", 25565));
        assertEquals(List.of(), VoiceServerConfigCheck.inspect(DEFAULTS + "[host.public]\nip = \"0.0.0.0\"\nport = 25565\n", 25565));
    }

    @Test
    void hostIpLanAddressWarns() {
        List<Finding> f = VoiceServerConfigCheck.inspect("[host]\nip = \"192.0.2.10\"\nport = 0\n", 25565);
        assertEquals(List.of(hostIpWarning("192.0.2.10")), f);
    }

    @Test
    void hostIpWildcardIsClean() {
        assertEquals(List.of(), VoiceServerConfigCheck.inspect("[host]\nip = \"0.0.0.0\"\n", 25565));
    }

    @Test
    void hostIpIpv6AnyWarns() {
        List<Finding> f = VoiceServerConfigCheck.inspect("[host]\nip = \"::\"\nport = 0\n", 25565);
        assertEquals(List.of(hostIpWarning("::")), f);
    }

    @Test
    void hostIpWithPublicWildcardStillWarns() {
        List<Finding> f = VoiceServerConfigCheck.inspect(
                "[host]\nip = \"192.0.2.10\"\nport = 0\n[host.public]\nip = \"0.0.0.0\"\nport = 0\n", 25565);
        assertEquals(List.of(hostIpWarning("192.0.2.10")), f);
    }

    @Test
    void publicWithoutIpWarnsWithDefault() {
        List<Finding> f = VoiceServerConfigCheck.inspect(DEFAULTS + "[host.public]\nport = 0\n", 25565);
        assertEquals(List.of(new Finding(Severity.WARN, "Cattail voice: Plasmo Voice [host.public] ip = \"127.0.0.1\" breaks "
                + "tailnet and LAN players. Set ip = \"0.0.0.0\" (clients then keep the address they joined with) or remove "
                + "[host.public].")), f);
    }

    @Test
    void absentHostTableIsClean() {
        assertEquals(List.of(), VoiceServerConfigCheck.inspect("", 25565));
        assertEquals(List.of(), VoiceServerConfigCheck.inspect("[other]\nx = 1\n", 25565));
    }

    @Test
    void movedPortAndPublicIpGiveTwoFindings() {
        List<Finding> f = VoiceServerConfigCheck.inspect(
                "[host]\nip = \"0.0.0.0\"\nport = 24454\n[host.public]\nip = \"127.0.0.1\"\nport = 0\n", 25565);
        assertEquals(2, f.size());
        assertTrue(f.get(0).message().startsWith("Cattail voice: Plasmo Voice [host] port is 24454 "));
        assertTrue(f.get(1).message().startsWith("Cattail voice: Plasmo Voice [host.public] ip = \"127.0.0.1\" "));
    }

    @Test
    void unparseableTomlWarnsAndDoesNotThrow() {
        List<Finding> f = VoiceServerConfigCheck.inspect("[host\nport = = 1", 25565);
        assertEquals(1, f.size());
        assertEquals(Severity.WARN, f.get(0).severity());
        assertTrue(f.get(0).message().startsWith("Cattail voice: could not parse config.toml: "));
    }

    @Test
    void missingFileWarns(@TempDir Path dir) {
        Path file = dir.resolve(VoiceServerConfigCheck.CONFIG_RELATIVE_PATH);
        assertEquals(List.of(new Finding(Severity.WARN,
                "Cattail voice: Plasmo Voice config not found at " + file + "; the voice server is not running.")),
                VoiceServerConfigCheck.inspect(file, 25565));
    }

    @Test
    void notLoadedIsOneInfoFindingWhateverTheConfig(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(VoiceServerConfigCheck.CONFIG_RELATIVE_PATH);
        // Missing file: no WARN, since without Plasmo Voice there is no config to check.
        assertEquals(List.of(VoiceServerConfigCheck.NOT_LOADED), VoiceServerConfigCheck.inspect(false, file, 25565));
        assertEquals(Severity.INFO, VoiceServerConfigCheck.NOT_LOADED.severity());
        assertTrue(VoiceServerConfigCheck.NOT_LOADED.message().startsWith("Cattail voice: Plasmo Voice is not loaded"));
        // A stale config with a problem is ignored too.
        Files.createDirectories(file.getParent());
        Files.writeString(file, "[host]\nport = 24454\n");
        assertEquals(List.of(VoiceServerConfigCheck.NOT_LOADED), VoiceServerConfigCheck.inspect(false, file, 25565));
    }

    @Test
    void loadedDelegatesToTheFileCheck(@TempDir Path dir) {
        Path file = dir.resolve(VoiceServerConfigCheck.CONFIG_RELATIVE_PATH);
        assertEquals(VoiceServerConfigCheck.inspect(file, 25565), VoiceServerConfigCheck.inspect(true, file, 25565));
    }

    @Test
    void fileIsReadAndInspected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(VoiceServerConfigCheck.CONFIG_RELATIVE_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "[host]\nport = 24454\n");
        assertEquals(Severity.WARN, VoiceServerConfigCheck.inspect(file, 25565).get(0).severity());
    }

    private static Finding hostIpWarning(String ip) {
        return new Finding(Severity.WARN, "Cattail voice: Plasmo Voice [host] ip = \"" + ip + "\" binds the voice server to "
                + "one address; tailnet players and LAN players reach the server on different addresses. Set ip = "
                + "\"0.0.0.0\" in config/plasmovoice/server/config.toml.");
    }
}
