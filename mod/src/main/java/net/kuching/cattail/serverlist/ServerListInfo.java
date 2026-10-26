package net.kuching.cattail.serverlist;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.kuching.cattail.text.SafeText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.status.ServerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a Cattail server tells players' server lists about itself: its Cattail version and the
 * owner's tooltip lines. It rides in the status (server-list ping) JSON as
 * {@code "cattail": {"v": "0.2.0", "lines": ["§aVoice chat on", "Restarts daily 2:00 AM ET"]}},
 * added by {@link #wrapStatusCodec} around {@link ServerStatus#CODEC} and carried on the status
 * object through {@link CattailStatusCarrier}.
 *
 * <p>A line holds {@code §} only as the start of a formatting code: where the owner wrote {@code &}
 * (or {@code §}) plus that code.
 * Values from the server config and from the network both go through {@link #sanitised}; the
 * decoder runs it again because the network is not trusted.
 *
 * @param version the server's Cattail version, 1 to {@value #MAX_VERSION} chars
 * @param lines   0 to {@value #MAX_LINES} tooltip lines, each 1 to {@value #MAX_LINE} chars
 */
public record ServerListInfo(String version, List<String> lines) {

    /** Longest version, in chars. */
    public static final int MAX_VERSION = 32;
    /** Most tooltip lines. */
    public static final int MAX_LINES = 6;
    /** Longest tooltip line, in chars, formatting codes included. */
    public static final int MAX_LINE = 64;

    /** The status JSON key of the {@code cattail} object. */
    public static final String FIELD = "cattail";

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerListInfo.class);

    /** The section sign Minecraft reads as the start of a formatting code. */
    private static final char SECTION = '§';

    /**
     * The {@code cattail} object: {@code {"v": <version>, "lines": [<line>, ...]}}, both fields
     * required, {@code lines} always written (as {@code []} when there are none). Decoding runs
     * {@link #sanitised} on what arrived, so an over-long value is clipped, never rejected; a value
     * of the wrong shape is a decode error, which {@link #wrapStatusCodec} treats as no info.
     */
    public static final Codec<ServerListInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Codec.STRING.fieldOf("v").forGetter(ServerListInfo::version),
                    Codec.STRING.listOf().fieldOf("lines").forGetter(ServerListInfo::lines))
            .apply(i, (version, lines) -> sanitised(version, lines, dropped -> {})));

    /** Takes the values as they are (see {@link #sanitised}); the list is copied and must hold no null. */
    public ServerListInfo {
        Objects.requireNonNull(version, "version");
        lines = List.copyOf(lines);
    }

    /**
     * Builds the info within the wire limits. The version has control and format characters turned
     * into spaces ({@link SafeText#withoutControls}) and its {@code §} formatting codes removed (it
     * is rendered as plain text, never styled), is trimmed, clipped to {@value #MAX_VERSION} chars,
     * and is {@code ?} when blank. Each line has control and format characters turned into spaces,
     * {@code &} plus a formatting code ({@code 0-9}, {@code a-f}, {@code k-o}, {@code r}, any case)
     * turned into {@code §} plus the lowercase code (any other {@code &} stays literal) and every
     * {@code §} with no formatting code after it removed, is trimmed and clipped to
     * {@value #MAX_LINE} chars (never between a {@code §} and its code, nor inside a surrogate
     * pair). A line that is then empty, or has no text besides its formatting codes, is left out,
     * and so is every line after the first {@value #MAX_LINES} kept ones.
     *
     * @param rawLines the lines as written, null meaning none; a null line counts as empty
     * @param dropped  receives one message per line left out or cut, numbered from 1 (e.g.
     *                 {@code line 3 is empty, left out})
     */
    public static ServerListInfo sanitised(String version, List<String> rawLines, Consumer<String> dropped) {
        String v = clip(withoutCodes(SafeText.withoutControls(version)).replace(String.valueOf(SECTION), "").strip(),
                MAX_VERSION).strip();
        List<String> kept = new ArrayList<>();
        List<String> raw = rawLines == null ? List.of() : rawLines;
        for (int i = 0; i < raw.size(); i++) {
            String which = "line " + (i + 1);
            String whole = withSectionCodes(SafeText.withoutControls(raw.get(i))).strip();
            String line = clip(whole, MAX_LINE).strip();
            if (line.isEmpty()) {
                dropped.accept(which + " is empty, left out");
            } else if (withoutCodes(line).isBlank()) {
                dropped.accept(which + " has only formatting codes, left out");
            } else if (kept.size() == MAX_LINES) {
                dropped.accept(which + " is past the limit of " + MAX_LINES + " lines, left out");
            } else {
                kept.add(line);
                if (whole.length() > MAX_LINE) {
                    dropped.accept(which + " is longer than " + MAX_LINE + " characters, cut");
                }
            }
        }
        return new ServerListInfo(v.isEmpty() ? "?" : v, kept);
    }

    /**
     * {@code vanilla} plus the {@value #FIELD} object: encoding writes the vanilla object, then adds
     * the entry when the status carries info; decoding is the vanilla decode, then a lenient read
     * of the entry onto the decoded status. Installed in place of {@link ServerStatus#CODEC} by
     * {@code ServerStatusMixin}, so every user of that field gets it: the server's cached status
     * JSON, the status packet's write and read, and No Chat Reports, which replaces that write and
     * read but still calls the codec it is handed.
     *
     * <p>Our entry never fails the status: an entry that cannot be encoded is left out, and one
     * that cannot be decoded (wrong type, wrong shape) leaves the status with no info, logged at
     * debug. Vanilla errors pass through unchanged.
     */
    public static Codec<ServerStatus> wrapStatusCodec(Codec<ServerStatus> vanilla) {
        return withInfoField(vanilla);
    }

    /**
     * {@code current} when it already is a codec from {@link #wrapStatusCodec} (nothing to do), else
     * {@link #wrapStatusCodec} around it. Other mods replace {@link ServerStatus#CODEC} from the same
     * static initialiser, in an order no mod controls (Better Compatibility Checker assigns a new
     * codec outright), so the mixins call this again just before the status is encoded or decoded;
     * a wrapper is never wrapped twice.
     */
    public static <S> Codec<S> ensureWrapped(Codec<S> current) {
        return current instanceof WithInfoField ? current : withInfoField(current);
    }

    /**
     * {@link #wrapStatusCodec} for any status type: the carrier is found with a runtime type check,
     * so unit tests, which run without mixins and so have no carrier on the real record, can drive
     * the same code with a stand-in carrier.
     */
    static <S> Codec<S> withInfoField(Codec<S> vanilla) {
        return new WithInfoField<>(vanilla);
    }

    /** The wrapper from {@link #withInfoField}, a named class so {@link #ensureWrapped} can recognise it. */
    private static final class WithInfoField<S> implements Codec<S> {

        private final Codec<S> vanilla;

        WithInfoField(Codec<S> vanilla) {
            this.vanilla = Objects.requireNonNull(vanilla, "vanilla");
        }

        @Override
        public <T> DataResult<T> encode(S input, DynamicOps<T> ops, T prefix) {
            return vanilla.encode(input, ops, prefix).map(encoded -> withEntry(encoded, input, ops));
        }

        @Override
        public <T> DataResult<Pair<S, T>> decode(DynamicOps<T> ops, T input) {
            return vanilla.decode(ops, input).map(decoded -> {
                carrier(decoded.getFirst()).ifPresent(c -> c.cattail$setInfo(readEntry(ops, input)));
                return decoded;
            });
        }

        @Override
        public String toString() {
            return "Cattail[" + vanilla + "]";
        }
    }

    /** The info a status carries; empty when it has none or is not a carrier (no mixin, as in unit tests). */
    public static Optional<ServerListInfo> of(ServerStatus status) {
        return carrier(status).flatMap(CattailStatusCarrier::cattail$info);
    }

    /** Through {@code Object}: a record is final, so a direct test against an interface a mixin adds does not compile. */
    private static Optional<CattailStatusCarrier> carrier(Object status) {
        return status instanceof CattailStatusCarrier c ? Optional.of(c) : Optional.empty();
    }

    /** {@code encoded} with the {@value #FIELD} entry added when {@code status} carries info, else unchanged. */
    private static <T> T withEntry(T encoded, Object status, DynamicOps<T> ops) {
        Optional<ServerListInfo> info = carrier(status).flatMap(CattailStatusCarrier::cattail$info);
        if (info.isEmpty()) {
            return encoded;
        }
        DataResult<T> merged = CODEC.encodeStart(ops, info.get())
                .flatMap(value -> ops.mergeToMap(encoded, ops.createString(FIELD), value));
        merged.ifError(e -> LOGGER.debug("Cattail: status sent without its {} entry: {}", FIELD, e.message()));
        return merged.result().orElse(encoded);
    }

    /**
     * The {@value #FIELD} entry of a status object, or empty when it is absent or malformed. Never
     * throws: it runs inside the vanilla status decode, which a throw would fail as a whole (the
     * packet read turns any decode failure into a failed ping).
     */
    private static <T> Optional<ServerListInfo> readEntry(DynamicOps<T> ops, T input) {
        try {
            Optional<T> value = ops.getMap(input).result().map(map -> map.get(FIELD));
            if (value.isEmpty()) {
                return Optional.empty();
            }
            DataResult<ServerListInfo> parsed = CODEC.parse(ops, value.get());
            parsed.ifError(e -> LOGGER.debug("Cattail: ignoring a malformed {} entry in a server status: {}", FIELD,
                    e.message()));
            return parsed.result();
        } catch (RuntimeException e) {
            LOGGER.debug("Cattail: ignoring a malformed {} entry in a server status", FIELD, e);
            return Optional.empty();
        }
    }

    /**
     * {@code &} plus a formatting code to {@code §} plus the lowercase code, any other {@code &}
     * kept; a {@code §} with no formatting code after it, a trailing one included, removed (vanilla's
     * text renderer would hide it together with the character after it, or drop it at the end, so
     * it never draws).
     */
    private static String withSectionCodes(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean codeNext = i + 1 < s.length() && ChatFormatting.getByCode(s.charAt(i + 1)) != null;
            if (c == '&' && codeNext) {
                b.append(SECTION).append(Character.toLowerCase(s.charAt(i + 1)));
                i++;
            } else if (c != SECTION || codeNext) {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** The text without its {@code §} formatting codes, to tell visible text from codes alone. */
    private static String withoutCodes(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == SECTION && i + 1 < s.length() && ChatFormatting.getByCode(s.charAt(i + 1)) != null) {
                i++;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** {@link SafeText#clip} (never inside a surrogate pair), also never between a {@code §} and its code. */
    private static String clip(String s, int max) {
        boolean splitsCode = s.length() > max && s.charAt(max - 1) == SECTION
                && ChatFormatting.getByCode(s.charAt(max)) != null;
        return SafeText.clip(s, splitsCode ? max - 1 : max);
    }
}
