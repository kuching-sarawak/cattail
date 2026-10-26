package net.kuching.cattail.specs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import net.kuching.cattail.deploy.Deployment;
import net.kuching.cattail.deploy.DeploymentSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SysInfoTest {

    private static final long GIB = 1L << 30;

    private static Properties sys() {
        Properties p = new Properties();
        p.setProperty("os.name", "Windows 11");
        p.setProperty("os.version", "10.0");
        p.setProperty("os.arch", "amd64");
        p.setProperty("java.version", "21.0.7");
        return p;
    }

    private static SysInfo.Hardware samplePc() {
        return new SysInfo.Hardware("Intel(R) Core(TM) i7-9700K CPU @ 4.00GHz", 8, 16, 64 * GIB - 200L * (1 << 20),
                List.of(new SysInfo.Gpu("Intel(R) UHD Graphics 630", GIB, "DriverVersion=31.0.101.2111"),
                        new SysInfo.Gpu("NVIDIA GeForce RTX 3060", 11 * GIB, "DriverVersion=32.0.15.6094")));
    }

    private static SysInfo sample() {
        return SysInfo.collect(samplePc(), "NVIDIA GeForce RTX 3060/PCIe/SSE2", sys(), 16 * GIB, 12,
                Optional.of("ComplementaryReimagined_r5.5.1.zip"), 451, "0.2.0");
    }

    @Test
    void collectBuildsTheRecordAndPicksTheGpuTheGameRendersOn() {
        SysInfo info = sample();
        assertEquals(new SysInfo("Intel(R) Core(TM) i7-9700K CPU @ 4.00GHz", 8, 16, 64 * GIB - 200L * (1 << 20),
                "NVIDIA GeForce RTX 3060", 11 * GIB, "32.0.15.6094", "Windows 11 10.0 amd64", "21.0.7",
                16 * GIB, 12, "ComplementaryReimagined_r5.5.1.zip", 451, "0.2.0"), info);
    }

    @Test
    void collectFallsBackToTheFirstCardThenToTheGlRenderer() {
        SysInfo.Hardware hw = new SysInfo.Hardware("cpu", 4, 8, 8 * GIB,
                List.of(new SysInfo.Gpu("Some Card", 2 * GIB, "")));
        SysInfo first = SysInfo.collect(hw, "Mesa Intel(R) Xe/whatever", sys(), GIB, 8, Optional.empty(), 1, "0.2.0");
        assertEquals("Some Card", first.gpu());
        assertEquals("unknown", first.driver());

        SysInfo none = SysInfo.collect(new SysInfo.Hardware("cpu", 4, 8, 8 * GIB, List.of()),
                "llvmpipe (LLVM 19.1.1, 256 bits)", sys(), GIB, 8, Optional.empty(), 1, "0.2.0");
        assertEquals("llvmpipe (LLVM 19.1.1, 256 bits)", none.gpu());
        assertEquals(0, none.vramBytes());
        assertEquals("", none.shaderpack());
    }

    @Test
    void logLineHasTheExactFormat() {
        assertEquals("CATTAILSPECS|Alice|Intel(R) Core(TM) i7-9700K CPU @ 4.00GHz|8c/16t|64 GiB"
                        + "|NVIDIA GeForce RTX 3060|11 GiB|32.0.15.6094|Windows 11 10.0 amd64|Java 21.0.7"
                        + "|heap 16 GiB|render 12|ComplementaryReimagined_r5.5.1.zip|451 mods|cattail 0.2.0"
                        + "|ip 100.101.102.103",
                SysInfo.toLogLine("Alice", sample(), "CATTAILSPECS", "100.101.102.103"));
    }

    @Test
    void logLineStartsWithTheGivenPrefix() {
        assertTrue(SysInfo.toLogLine("Alice", sample(), "CATTAILSPECS", "unknown").startsWith("CATTAILSPECS|Alice|"));
        assertTrue(SysInfo.toLogLine("Alice", sample(), "EXAMPLESPECS", "unknown").startsWith("EXAMPLESPECS|Alice|"));
    }

    @Test
    void prefixDefaultsWhenGeneric() {
        assertEquals("CATTAILSPECS|", SysInfo.linePrefix(new Deployment.Generic()));
    }

    @Test
    void prefixComesFromSettings() {
        DeploymentSettings settings = new DeploymentSettings("tskey-auth-EXAMPLE", Optional.empty(), "tag:example",
                "EXAMPLE", Optional.empty());
        assertEquals("EXAMPLE|", SysInfo.linePrefix(new Deployment.Configured(settings)));
    }

    @Test
    void prefixDefaultsWhenInvalid() {
        assertEquals("CATTAILSPECS|", SysInfo.linePrefix(new Deployment.Invalid("specs.prefix")));
    }

    @Test
    void logLineSanitisesFieldsAndSaysNoneWithoutAShaderpack() {
        String gpu70 = "G".repeat(70);
        SysInfo info = new SysInfo("A|B\nC", 6, 12, 15L * GIB / 2, gpu70, 3 * GIB / 2, "1.2", "Linux 6.8 amd64",
                "21", 4 * GIB, 8, "", 3, "0.2.0");
        String line = SysInfo.toLogLine("P|x", info, "CATTAILSPECS", "192.0.2.7|\n");
        assertEquals("CATTAILSPECS|P/x|A/B C|6c/12t|7.5 GiB|" + "G".repeat(64)
                + "|1.5 GiB|1.2|Linux 6.8 amd64|Java 21|heap 4 GiB|render 8|none|3 mods|cattail 0.2.0|ip 192.0.2.7/", line);
        assertFalse(line.contains("\n"));
        assertEquals("Shader pack.zip", SysInfo.field("Shader\u202Epack.zip"), "a format character becomes a space");
    }

    @Test
    void logLineIsCappedAt1024Bytes() {
        String big = "é".repeat(64); // 2 bytes each in UTF-8
        SysInfo info = new SysInfo(big, 1, 1, GIB, big, GIB, big, big, big, GIB, 2, big, 1, big);
        String line = SysInfo.toLogLine(big, info, "CATTAILSPECS", big);
        assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 1024, "line is at most 1024 bytes");
        assertTrue(line.startsWith("CATTAILSPECS|"));
    }

    @Test
    void logLineCapCutsAtTheLastWholeCodePointThatFits() {
        // "CATTAILSPECS|" is 13 bytes; each remaining field adds 2-byte characters, so the cut
        // lands exactly where the next character would exceed 1024 bytes, never mid pair.
        String big = "\u00e9".repeat(64);
        SysInfo info = new SysInfo(big, 1, 1, GIB, big, GIB, big, big, big, GIB, 2, big, 1, big);
        String line = SysInfo.toLogLine(big, info, "CATTAILSPECS", big);
        int bytes = line.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 1024 && bytes >= 1023, "filled to the cap, was " + bytes);
        String astral = "\ud83d\ude00".repeat(300); // 4 bytes each
        String cut = SysInfo.toLogLine("P", new SysInfo(astral, 1, 1, GIB, astral, GIB, astral, astral, astral,
                GIB, 2, astral, 1, astral), "CATTAILSPECS", astral);
        assertTrue(cut.getBytes(StandardCharsets.UTF_8).length <= 1024);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)), "never ends mid surrogate pair");
    }

    @Test
    void hostOfGivesTheBareAddressOrUnknown() throws java.net.UnknownHostException {
        assertEquals("100.101.102.103", SysInfo.hostOf(new InetSocketAddress("100.101.102.103", 25565)));
        assertEquals("0:0:0:0:0:0:0:1", SysInfo.hostOf(new InetSocketAddress("::1", 25565)));
        assertEquals("unknown", SysInfo.hostOf(InetSocketAddress.createUnresolved("mc.example.com", 25565)),
                "an unresolved address is a name, which a parser anchored on an address must not see");
        byte[] linkLocal = {(byte) 0xfe, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        java.net.Inet6Address scoped = java.net.Inet6Address.getByAddress(null, linkLocal, 7);
        assertTrue(scoped.getHostAddress().contains("%7"), "the JDK renders the scope: " + scoped.getHostAddress());
        assertEquals("fe80:0:0:0:0:0:0:1", SysInfo.hostOf(new InetSocketAddress(scoped, 25565)), "no zone");
        assertEquals("unknown", SysInfo.hostOf(null));
        assertEquals("unknown", SysInfo.hostOf(UnixDomainSocketAddress.of("/tmp/x.sock")));
    }

    @Test
    void requestPayloadIsEmptyAndRoundTrips() {
        ByteBuf buf = Unpooled.buffer();
        SpecsRequestPayload.STREAM_CODEC.encode(buf, SpecsRequestPayload.INSTANCE);
        assertEquals(0, buf.readableBytes());
        assertEquals(SpecsRequestPayload.INSTANCE, SpecsRequestPayload.STREAM_CODEC.decode(buf));
        assertEquals("cattail:specs_request", SpecsRequestPayload.TYPE.id().toString());
    }

    @Test
    void payloadCodecRoundTrips() {
        SysInfoPayload payload = new SysInfoPayload(sample());
        ByteBuf buf = Unpooled.buffer();
        SysInfoPayload.STREAM_CODEC.encode(buf, payload);
        assertEquals(payload, SysInfoPayload.STREAM_CODEC.decode(buf));
        assertEquals(0, buf.readableBytes());
        assertEquals("cattail:sysinfo", SysInfoPayload.TYPE.id().toString());
    }

    @Test
    void irisShaderpackIsReadFromItsConfigOnlyWhenIrisIsLoadedAndShadersAreOn(@TempDir Path game) throws IOException {
        Path cfg = game.resolve("config");
        Files.createDirectories(cfg);
        assertEquals(Optional.empty(), SysInfo.irisShaderpack(game, true), "no config file");

        Files.writeString(cfg.resolve("iris.properties"), "enableShaders=true\nshaderPack=BSL_v8.4.zip\n");
        assertEquals(Optional.of("BSL_v8.4.zip"), SysInfo.irisShaderpack(game, true));
        assertEquals(Optional.empty(), SysInfo.irisShaderpack(game, false), "iris not loaded");

        Files.writeString(cfg.resolve("iris.properties"), "enableShaders=false\nshaderPack=BSL_v8.4.zip\n");
        assertEquals(Optional.empty(), SysInfo.irisShaderpack(game, true), "shaders switched off");
    }
}
