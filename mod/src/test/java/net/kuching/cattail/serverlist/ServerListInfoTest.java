package net.kuching.cattail.serverlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.protocol.status.ServerStatus;
import org.junit.jupiter.api.Test;

class ServerListInfoTest {

    private static final ServerListInfo INFO =
            new ServerListInfo("0.2.0", List.of("§aVoice chat on", "Restarts daily 2:00 AM ET"));
    private static final String INFO_JSON =
            "{\"v\":\"0.2.0\",\"lines\":[\"§aVoice chat on\",\"Restarts daily 2:00 AM ET\"]}";

    /**
     * A {@link ServerStatus} as {@code ServerStatusMixin} makes it: unit tests run without mixins,
     * so the real record never implements the carrier here. This wraps a real status (so the
     * vanilla fields go through the real {@link ServerStatus#CODEC}) and carries the info the
     * mixin-added field would.
     */
    private static final class CarriedStatus implements CattailStatusCarrier {
        private final ServerStatus status;
        private Optional<ServerListInfo> info = Optional.empty();

        CarriedStatus(ServerStatus status) {
            this.status = status;
        }

        ServerStatus status() {
            return status;
        }

        @Override
        public Optional<ServerListInfo> cattail$info() {
            return info;
        }

        @Override
        public void cattail$setInfo(Optional<ServerListInfo> info) {
            this.info = info;
        }
    }

    /** The real vanilla codec wrapped by the same code as {@link ServerListInfo#wrapStatusCodec}. */
    private static final Codec<CarriedStatus> CARRIED_CODEC =
            ServerListInfo.withInfoField(ServerStatus.CODEC.xmap(CarriedStatus::new, CarriedStatus::status));

    /**
     * A status for the real vanilla codec. Its description is the empty component, which the codec
     * leaves out: encoding or decoding any other component reaches the game's registries
     * ({@code ItemStack} through the hover event), and NeoForge's {@code Bootstrap} needs the mod
     * loader, which unit tests do not have. The wrapper never touches the description.
     */
    private static ServerStatus sample() {
        return new ServerStatus(CommonComponents.EMPTY, Optional.of(new ServerStatus.Players(20, 3, List.of())),
                Optional.of(new ServerStatus.Version("1.21.1", 767)), Optional.empty(), true, true);
    }

    private static JsonObject vanillaJson(ServerStatus status) {
        return ServerStatus.CODEC.encodeStart(JsonOps.INSTANCE, status).getOrThrow().getAsJsonObject();
    }

    private static JsonObject withCattail(JsonObject vanilla, String cattail) {
        JsonObject json = vanilla.deepCopy();
        json.add(ServerListInfo.FIELD, JsonParser.parseString(cattail));
        return json;
    }

    @Test
    void sanitisedConvertsAmpersandCodesAndClips() {
        List<String> dropped = new ArrayList<>();
        ServerListInfo info = ServerListInfo.sanitised(" 0.2.0\n", List.of(
                "&aVoice &lon",
                "x".repeat(70),
                "Rock & roll &",
                "&zebra &&A",
                "  tab\there end  ",
                "§Araw section"), dropped::add);
        assertEquals("0.2.0", info.version());
        assertEquals(List.of(
                "§aVoice §lon",
                "x".repeat(64),
                "Rock & roll &",
                "&zebra &§a",
                "tab here end",
                "§Araw section"), info.lines(),
                "& plus a code (any case) becomes § plus the lowercase code; a lone &, && and &z stay literal;"
                        + " a § code the owner typed is kept as it is");
        assertEquals(List.of("line 2 is longer than 64 characters, cut"), dropped);

        dropped.clear();
        ServerListInfo clips = ServerListInfo.sanitised("0.2.0", List.of(
                "x".repeat(63) + "&aYY",
                "x".repeat(63) + "😀",
                "x".repeat(63) + " " + "y".repeat(4)), dropped::add);
        assertEquals(List.of("x".repeat(63), "x".repeat(63), "x".repeat(63)), clips.lines(),
                "a clip never splits a §-code or a surrogate pair and leaves no trailing space");
        assertEquals(List.of("line 1 is longer than 64 characters, cut", "line 2 is longer than 64 characters, cut",
                "line 3 is longer than 64 characters, cut"), dropped);

        dropped.clear();
        assertEquals("v".repeat(ServerListInfo.MAX_VERSION),
                ServerListInfo.sanitised("v".repeat(40), List.of(), dropped::add).version());
        assertEquals("?", ServerListInfo.sanitised(" \t ", List.of(), dropped::add).version());
        assertEquals("0.2.0", ServerListInfo.sanitised("§l0.2.0§r", List.of(), dropped::add).version(),
                "the version is plain text: section signs are removed, not styled");
        ServerListInfo nothing = ServerListInfo.sanitised(null, null, dropped::add);
        assertEquals("?", nothing.version());
        assertEquals(List.of(), nothing.lines());
        assertEquals(List.of(), dropped);
    }

    @Test
    void sanitisedDropsEmptyAndExtraLines() {
        List<String> dropped = new ArrayList<>();
        ServerListInfo info = ServerListInfo.sanitised("0.2.0", Arrays.asList(
                "one", "", "   ", "&a&b", "&a".repeat(32), null, "two"), dropped::add);
        assertEquals(List.of("one", "two"), info.lines());
        assertEquals(List.of(
                "line 2 is empty, left out",
                "line 3 is empty, left out",
                "line 4 has only formatting codes, left out",
                "line 5 has only formatting codes, left out",
                "line 6 is empty, left out"), dropped,
                "a 64-char line of 32 &a codes has no visible text, so it is left out");

        dropped.clear();
        List<String> eight = List.of("1", "2", "3", "4", "5", "6", "7", "8");
        ServerListInfo first6 = ServerListInfo.sanitised("0.2.0", eight, dropped::add);
        assertEquals(eight.subList(0, ServerListInfo.MAX_LINES), first6.lines());
        assertEquals(List.of("line 7 is past the limit of 6 lines, left out",
                "line 8 is past the limit of 6 lines, left out"), dropped);

        dropped.clear();
        ServerListInfo skipsEmpty = ServerListInfo.sanitised("0.2.0",
                List.of("1", "", "2", "3", "4", "5", "6", "7"), dropped::add);
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), skipsEmpty.lines(), "a dropped line does not use a slot");
        assertEquals(List.of("line 2 is empty, left out", "line 8 is past the limit of 6 lines, left out"), dropped);

        dropped.clear();
        ServerListInfo.sanitised("0.2.0", List.of("1", "2", "3", "4", "5", "6", "x".repeat(70)), dropped::add);
        assertEquals(List.of("line 7 is past the limit of 6 lines, left out"), dropped,
                "one message per line: a line left out is not also reported as cut");
    }

    /**
     * Vanilla's text renderer hides a {@code §} with no formatting code after it together with the
     * character after it (or drops it at the end), so sanitising removes such a sign.
     */
    @Test
    void sanitisedRemovesASectionSignWithNoCodeAfterIt() {
        List<String> dropped = new ArrayList<>();
        ServerListInfo info = ServerListInfo.sanitised("0.2.0", List.of(
                "Price 5§ each",
                "End §",
                "§zebra",
                "§§aGreen",
                "§&aGreen",
                "§Akept",
                "§"), dropped::add);
        assertEquals(List.of("Price 5 each", "End", "zebra", "§aGreen", "§aGreen", "§Akept"), info.lines());
        assertEquals(List.of("line 7 is empty, left out"), dropped);
    }

    /** A bidirectional override would reorder the text after it; like a control character, it becomes a space. */
    @Test
    void sanitisedTurnsFormatCharactersIntoSpaces() {
        List<String> dropped = new ArrayList<>();
        ServerListInfo info = ServerListInfo.sanitised("0.2.0\u202E", List.of("Voice\u202Echat on", "\u202E"),
                dropped::add);
        assertEquals("0.2.0", info.version());
        assertEquals(List.of("Voice chat on"), info.lines());
        assertEquals(List.of("line 2 is empty, left out"), dropped);
    }

    @Test
    void codecRoundTripsThroughJson() {
        JsonElement json = ServerListInfo.CODEC.encodeStart(JsonOps.INSTANCE, INFO).getOrThrow();
        assertEquals(INFO_JSON, json.toString());
        assertEquals(INFO, ServerListInfo.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());

        ServerListInfo noLines = new ServerListInfo("0.2.0", List.of());
        JsonElement empty = ServerListInfo.CODEC.encodeStart(JsonOps.INSTANCE, noLines).getOrThrow();
        assertEquals("{\"v\":\"0.2.0\",\"lines\":[]}", empty.toString(), "no lines are sent as []");
        assertEquals(noLines, ServerListInfo.CODEC.parse(JsonOps.INSTANCE, empty).getOrThrow());

        String tooMuch = "{\"v\":\"" + "v".repeat(40) + "\",\"lines\":[\"" + "x".repeat(70)
                + "\",\"\",\"&aGreen\",\"3\",\"4\",\"5\",\"6\",\"7\"]}";
        ServerListInfo clipped = ServerListInfo.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(tooMuch))
                .getOrThrow();
        assertEquals(new ServerListInfo("v".repeat(32), List.of("x".repeat(64), "§aGreen", "3", "4", "5", "6")),
                clipped, "decode sanitises again: an over-long network value is clipped, never rejected");
    }

    @Test
    void wrappedStatusCodecAddsAndReadsTheField() {
        assertEquals("cattail", ServerListInfo.FIELD);
        ServerStatus status = sample();
        JsonObject vanilla = vanillaJson(status);
        assertTrue(vanilla.has("players") && vanilla.has("version") && vanilla.has("enforcesSecureChat")
                && vanilla.has("isModded"), "the sample exercises the vanilla fields: " + vanilla);

        CarriedStatus carried = new CarriedStatus(status);
        carried.cattail$setInfo(Optional.of(INFO));
        JsonElement encoded = CARRIED_CODEC.encodeStart(JsonOps.INSTANCE, carried).getOrThrow();
        assertEquals(withCattail(vanilla, INFO_JSON), encoded, "the vanilla object plus the cattail entry");
        CarriedStatus decoded = CARRIED_CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(status, decoded.status(), "vanilla fields intact");
        assertEquals(Optional.of(INFO), decoded.cattail$info());

        JsonElement none = CARRIED_CODEC.encodeStart(JsonOps.INSTANCE, new CarriedStatus(status)).getOrThrow();
        assertEquals(vanilla, none, "no info, no cattail key");
        assertEquals(Optional.empty(), CARRIED_CODEC.parse(JsonOps.INSTANCE, vanilla).getOrThrow().cattail$info());

        // No Chat Reports adds its own key to the JSON the wrapped codec wrote, and reads the
        // status through the same codec: both keys survive, and the extra key is ignored.
        JsonObject withNcr = withCattail(vanilla, INFO_JSON);
        withNcr.addProperty("preventsChatReports", true);
        assertEquals(Optional.of(INFO), CARRIED_CODEC.parse(JsonOps.INSTANCE, withNcr).getOrThrow().cattail$info());

        // The real codec as the mixin wraps it. Without the mixin (as here) the record is no
        // carrier: it encodes exactly as vanilla, decodes a cattail JSON without throwing, and
        // of(...) reads nothing.
        Codec<ServerStatus> wrapped = ServerListInfo.wrapStatusCodec(ServerStatus.CODEC);
        assertEquals(vanilla, wrapped.encodeStart(JsonOps.INSTANCE, status).getOrThrow());
        ServerStatus real = wrapped.parse(JsonOps.INSTANCE, withNcr).getOrThrow();
        assertEquals(status, real);
        assertEquals(Optional.empty(), ServerListInfo.of(real));
    }

    /** Another mod can replace the status codec after the class loaded: re-wrapping must be safe to repeat. */
    @Test
    void ensureWrappedWrapsOnceAndLeavesAWrapperAlone() {
        Codec<ServerStatus> wrapped = ServerListInfo.ensureWrapped(ServerStatus.CODEC);
        assertNotSame(ServerStatus.CODEC, wrapped, "a plain codec gets wrapped");
        assertSame(wrapped, ServerListInfo.ensureWrapped(wrapped), "a wrapper is returned as is, never wrapped twice");
        assertNotSame(wrapped, ServerListInfo.ensureWrapped(ServerStatus.CODEC.xmap(s -> s, s -> s)),
                "a fresh codec from another mod is wrapped again");
    }

    @Test
    void wrappedStatusCodecIgnoresMalformedField() {
        ServerStatus status = sample();
        JsonObject vanilla = vanillaJson(status);
        Codec<ServerStatus> wrapped = ServerListInfo.wrapStatusCodec(ServerStatus.CODEC);
        for (String bad : List.of("\"x\"", "[]", "{\"v\":1}", "{\"lines\":[1,2]}", "{\"v\":\"0.2.0\",\"lines\":[1,2]}",
                "{\"v\":\"0.2.0\",\"lines\":[\"ok\",2]}", "{\"v\":\"0.2.0\",\"lines\":\"x\"}", "{\"v\":\"0.2.0\"}",
                "{}", "null", "42", "true")) {
            JsonObject json = withCattail(vanilla, bad);
            CarriedStatus carried = CARRIED_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertEquals(status, carried.status(), "vanilla fields intact for cattail = " + bad);
            assertEquals(Optional.empty(), carried.cattail$info(), "no info for cattail = " + bad);
            assertEquals(status, wrapped.parse(JsonOps.INSTANCE, json).getOrThrow(), "real codec, cattail = " + bad);
        }
    }
}
