package net.kuching.cattail.serverlist;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kuching.cattail.dns.Route;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

/**
 * The icon's decisions and tooltip. Unit tests load no language, so translatable lines are checked
 * by key, arguments and style, never by their English text; literal lines are checked by text and
 * by the style each piece resolves to (a piece's own style over its parents', as the tooltip draws it).
 */
class ServerListTooltipTest {

    private static final ServerListInfo INFO = new ServerListInfo("0.2.0", List.of("Voice chat on"));
    private static final Route ROUTE = new Route("mc.example.com", address("100.101.102.103"), 25565, 25565);
    private static final TextColor GREEN = TextColor.fromLegacyFormat(ChatFormatting.GREEN);
    private static final TextColor GRAY = TextColor.fromLegacyFormat(ChatFormatting.GRAY);

    /** One drawn piece of text and the style it is drawn with. */
    private record Piece(String text, TextColor color, boolean bold) {
    }

    // styled

    @Test
    void styledAppliesAColourAndThenBoldOnTopOfIt() {
        assertEquals(List.of(new Piece("Voice ", GREEN, false), new Piece("on", GREEN, true)),
                pieces(ServerListTooltip.styled("§aVoice §lon")));
    }

    @Test
    void styledKeepsATrailingSectionSignAsText() {
        assertEquals(List.of(new Piece("End §", null, false)), pieces(ServerListTooltip.styled("End §")));
    }

    @Test
    void styledKeepsAnUnknownCodeAsText() {
        assertEquals(List.of(new Piece("§zNot a code", null, false)), pieces(ServerListTooltip.styled("§zNot a code")));
    }

    @Test
    void styledResetReturnsToTheBaseStyle() {
        assertEquals(List.of(new Piece("Loud", GREEN, true), new Piece(" quiet", null, false)),
                pieces(ServerListTooltip.styled("§a§lLoud§r quiet")));
    }

    @Test
    void styledReadsUppercaseCodes() {
        assertEquals(List.of(new Piece("Green", GREEN, false)), pieces(ServerListTooltip.styled("§AGreen")));
    }

    @Test
    void styledOfPlainTextIsThatText() {
        assertEquals("Restarts daily 2:00 AM ET", ServerListTooltip.styled("Restarts daily 2:00 AM ET").getString());
        assertEquals("", ServerListTooltip.styled("").getString());
    }

    // shows and dim

    @Test
    void showsForInfoOrARoute() {
        assertTrue(ServerListTooltip.shows(view(INFO, null, false)));
        assertTrue(ServerListTooltip.shows(view(null, ROUTE, false)));
        assertTrue(ServerListTooltip.shows(view(INFO, ROUTE, true)));
        assertFalse(ServerListTooltip.shows(view(null, null, false)));
    }

    @Test
    void dimOnlyForARouteWhoseHelperIsNotReady() {
        assertTrue(ServerListTooltip.dim(view(null, ROUTE, false)));
        assertTrue(ServerListTooltip.dim(view(INFO, ROUTE, false)));
        assertFalse(ServerListTooltip.dim(view(INFO, ROUTE, true)));
        assertFalse(ServerListTooltip.dim(view(INFO, null, false)));
        assertFalse(ServerListTooltip.dim(view(null, null, false)));
    }

    // iconLeft: a row ending at x = 400, with and without a player count

    /** No count: the right edge 4 px left of the ping bars, which start 15 px left of the row's end. */
    @Test
    void iconLeftWithoutACountSitsLeftOfThePingBars() {
        assertEquals(400 - 15 - 4 - 14, ServerListTooltip.iconLeft(400, 0, 0));
        assertEquals(400 - 15 - 4 - 14 - 7, ServerListTooltip.iconLeft(400, 0, -7));
    }

    /** A count ends 5 px left of the ping bars; the icon's right edge sits 4 px left of the count. */
    @Test
    void iconLeftSitsLeftOfThePlayerCount() {
        assertEquals(400 - 15 - 5 - 20 - 4 - 14, ServerListTooltip.iconLeft(400, 20, 0));
        assertEquals(400 - 15 - 5 - 20 - 4 - 14 + 3, ServerListTooltip.iconLeft(400, 20, 3));
        assertEquals(400 - 15 - 5 - 20 - 4, ServerListTooltip.iconLeft(400, 20, 0) + ServerListTooltip.ICON_SIZE,
                "the icon ends before the count starts, so it never covers it");
    }

    // lines: the four cases

    @Test
    void infoAndReadyRouteGiveHeaderGreenLinkAndOwnerLines() {
        List<Component> lines = ServerListTooltip.lines(view(INFO, ROUTE, true));
        assertEquals(3, lines.size());
        assertHeader(lines.get(0), "cattail.icon.header", "0.2.0");
        assertLink(lines.get(1), GREEN);
        assertEquals("• Voice chat on", lines.get(2).getString());
        assertEquals(List.of(new Piece("• ", GRAY, false), new Piece("Voice chat on", GRAY, false)), pieces(lines.get(2)));
    }

    @Test
    void aRouteWhoseHelperIsNotReadyHasAGreyLink() {
        List<Component> lines = ServerListTooltip.lines(view(INFO, ROUTE, false));
        assertLink(lines.get(1), GRAY);
    }

    @Test
    void infoOnlyGivesHeaderAndOwnerLinesWithNoLink() {
        List<Component> lines = ServerListTooltip.lines(view(INFO, null, false));
        assertEquals(2, lines.size());
        assertHeader(lines.get(0), "cattail.icon.header", "0.2.0");
        assertEquals("• Voice chat on", lines.get(1).getString());
    }

    @Test
    void routeOnlyGivesTheUnknownHeaderAndTheLink() {
        List<Component> lines = ServerListTooltip.lines(view(null, ROUTE, true));
        assertEquals(2, lines.size());
        assertHeader(lines.get(0), "cattail.icon.header.unknown");
        assertLink(lines.get(1), GREEN);
    }

    @Test
    void neitherGivesNoLines() {
        assertEquals(List.of(), ServerListTooltip.lines(view(null, null, false)));
    }

    /** {@code lines = []} with {@code showIcon = true}: the icon shows with the header alone. */
    @Test
    void infoWithNoLinesGivesTheHeaderAlone() {
        List<Component> lines = ServerListTooltip.lines(view(new ServerListInfo("0.2.0", List.of()), null, false));
        assertEquals(1, lines.size());
        assertHeader(lines.get(0), "cattail.icon.header", "0.2.0");
    }

    /** An owner line's own codes over the grey: a colour replaces it, {@code §r} returns to it. */
    @Test
    void ownerLineCodesApplyOverTheGrey() {
        ServerListInfo info = new ServerListInfo("0.2.0", List.of("§aVoice §lon§r, ask"));
        List<Component> lines = ServerListTooltip.lines(view(info, null, false));
        assertEquals(List.of(new Piece("• ", GRAY, false), new Piece("Voice ", GREEN, false),
                new Piece("on", GREEN, true), new Piece(", ask", GRAY, false)), pieces(lines.get(1)));
    }

    /**
     * Owner lines as written in the server config, through the same sanitising the server and the
     * client run: an {@code &} at the end of a line, the first of {@code &&}, and one before a letter
     * that is no formatting code all stay literal text; only {@code &} plus a code styles.
     */
    @Test
    void ampersandsThatAreNoCodeStayLiteral() {
        ServerListInfo info = ServerListInfo.sanitised("0.2.0", List.of("Fish & chips &", "&&aGreen", "&zebra"), d -> {
        });
        List<Component> lines = ServerListTooltip.lines(view(info, null, false));
        assertEquals(4, lines.size());
        assertEquals("• Fish & chips &", lines.get(1).getString());
        assertEquals(List.of(new Piece("• ", GRAY, false), new Piece("&", GRAY, false), new Piece("Green", GREEN, false)),
                pieces(lines.get(2)));
        assertEquals("• &zebra", lines.get(3).getString());
        assertEquals(List.of(new Piece("• ", GRAY, false), new Piece("&zebra", GRAY, false)), pieces(lines.get(3)));
    }

    /** Every key the tooltip uses is in the mod's English lang file, with a slot for its argument. */
    @Test
    void everyTooltipKeyIsInTheLangFile() throws IOException {
        JsonObject lang;
        try (InputStream in = ServerListTooltip.class.getResourceAsStream("/assets/cattail/lang/en_us.json")) {
            assertNotNull(in, "en_us.json");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<Component> lines = new ArrayList<>(ServerListTooltip.lines(view(INFO, ROUTE, true)));
        lines.addAll(ServerListTooltip.lines(view(null, ROUTE, false)));
        for (Component line : lines) {
            if (line.getContents() instanceof TranslatableContents t) {
                assertTrue(lang.has(t.getKey()), t.getKey() + " is missing from en_us.json");
                assertEquals(t.getArgs().length, lang.get(t.getKey()).getAsString().split("%s", -1).length - 1,
                        t.getKey() + " has one %s per argument");
            }
        }
    }

    private static ServerListTooltip.View view(ServerListInfo info, Route route, boolean helperReady) {
        return new ServerListTooltip.View(Optional.ofNullable(info), Optional.ofNullable(route), helperReady);
    }

    private static void assertHeader(Component header, String key, Object... args) {
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class, header.getContents());
        assertEquals(key, contents.getKey());
        assertArrayEquals(args, contents.getArgs());
        assertTrue(header.getStyle().isBold(), "the header is bold");
    }

    private static void assertLink(Component link, TextColor color) {
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class, link.getContents());
        assertEquals("cattail.label.tooltip", contents.getKey());
        assertArrayEquals(new Object[] {"100.101.102.103"}, contents.getArgs());
        assertEquals(color, link.getStyle().getColor());
    }

    /** The non-empty literal pieces of {@code c}, each with the style it resolves to. */
    private static List<Piece> pieces(Component c) {
        List<Piece> out = new ArrayList<>();
        c.visit((style, text) -> {
            if (!text.isEmpty()) {
                out.add(new Piece(text, style.getColor(), style.isBold()));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal); // a dotted-quad literal: no DNS query
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
