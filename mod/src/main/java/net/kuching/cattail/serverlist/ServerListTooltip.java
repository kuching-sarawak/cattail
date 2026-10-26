package net.kuching.cattail.serverlist;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.kuching.cattail.dns.Route;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * The Cattail icon on a server-list row: whether it shows, whether it is dim, where it sits, and its
 * tooltip. Pure (text components only, no client classes), so unit tests drive it without the game;
 * the server-list entry mixin builds a {@link View} per row and draws what this decides.
 */
public final class ServerListTooltip {

    /** The icon's width and height, in GUI pixels. */
    public static final int ICON_SIZE = 14;
    /** Width of vanilla's ping bars plus their 5 px right margin. */
    static final int PING_BARS = 15;
    /** Vanilla's gap between the player-count text and the ping bars. */
    static final int STATUS_GAP = 5;
    /** Gap between the icon and the player count (or the ping bars) to its right. */
    static final int ICON_GAP = 4;

    private static final char SECTION = '§';

    private ServerListTooltip() {
    }

    /**
     * What a server-list row knows about Cattail.
     *
     * @param info        the {@code cattail} object of the row's last status response; empty when it had none
     * @param route       the row's cached route; empty when Cattail does not route the server
     * @param helperReady whether the helper is READY for {@code route}
     */
    public record View(Optional<ServerListInfo> info, Optional<Route> route, boolean helperReady) {

        /** Rejects null optionals. */
        public View {
            Objects.requireNonNull(info, "info");
            Objects.requireNonNull(route, "route");
        }
    }

    /** Whether the row shows the icon: its status carried the {@code cattail} object, or Cattail routes it. */
    public static boolean shows(View v) {
        return v.info().isPresent() || v.route().isPresent();
    }

    /** Whether the icon is the dim one: the row is routed and the helper is not ready for that route. */
    public static boolean dim(View v) {
        return v.route().isPresent() && !v.helperReady();
    }

    /**
     * The tooltip, in order: the bold header {@code Cattail <version>} ({@code Cattail} without
     * info); for a routed row {@code Via Tailscale · <target ip>}, green when the helper is ready and
     * grey otherwise; then each owner line as {@code • <line>} in grey with the line's own
     * formatting codes applied over it. Empty when the icon does not show.
     */
    public static List<Component> lines(View v) {
        if (!shows(v)) {
            return List.of();
        }
        Optional<ServerListInfo> info = v.info();
        List<Component> out = new ArrayList<>();
        out.add(info.map(i -> Component.translatable("cattail.icon.header", i.version()))
                .orElseGet(() -> Component.translatable("cattail.icon.header.unknown"))
                .withStyle(ChatFormatting.BOLD));
        v.route().ifPresent(r -> out.add(Component.translatable("cattail.label.tooltip", r.target().getHostAddress())
                .withStyle(v.helperReady() ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
        info.ifPresent(i -> i.lines()
                .forEach(line -> out.add(Component.literal("• ").withStyle(ChatFormatting.GRAY).append(styled(line)))));
        return List.copyOf(out);
    }

    /**
     * The icon's left edge on a row whose right edge is {@code rowRight} ({@code x + width}): the
     * icon's right edge {@value #ICON_GAP} px left of the player count ({@code statusWidth} px wide,
     * ending {@value #STATUS_GAP} px left of the ping bars), or of the ping bars when there is no
     * count, plus {@code offsetX}. Nothing of vanilla's draws there, so the icon never covers the
     * count; other mods' row icons are moved out of the way with the client's offsets.
     *
     * @param statusWidth the width of the text vanilla draws left of the ping bars, 0 for none
     */
    public static int iconLeft(int rowRight, int statusWidth, int offsetX) {
        int pingBarsLeft = rowRight - PING_BARS;
        int rightNeighbour = statusWidth > 0 ? pingBarsLeft - STATUS_GAP - statusWidth : pingBarsLeft;
        return rightNeighbour - ICON_GAP - ICON_SIZE + offsetX;
    }

    /**
     * {@code legacy} as a component: each {@code §} plus a formatting code (any case) changes the
     * style of the text after it the way vanilla reads such codes (a colour also clears bold, italic
     * and the rest; {@code §r} returns to the base style, which the parent component supplies), and
     * the text runs become literal siblings. A {@code §} before any other character, or at the end,
     * is kept as text, a correct parse of such input; {@link ServerListInfo#sanitised} removes those
     * before an owner line gets here.
     */
    public static Component styled(String legacy) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            ChatFormatting code = c == SECTION && i + 1 < legacy.length() ? ChatFormatting.getByCode(legacy.charAt(i + 1)) : null;
            if (code == null) {
                run.append(c);
                continue;
            }
            appendRun(out, run, style);
            style = code == ChatFormatting.RESET ? Style.EMPTY : style.applyLegacyFormat(code);
            i++;
        }
        appendRun(out, run, style);
        return out;
    }

    private static void appendRun(MutableComponent out, StringBuilder run, Style style) {
        if (!run.isEmpty()) {
            out.append(Component.literal(run.toString()).setStyle(style));
            run.setLength(0);
        }
    }
}
