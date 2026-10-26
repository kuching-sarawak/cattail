package net.kuching.cattail.deploy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The owner's deployment values, read from the {@value #RESOURCE} resource that {@code inject}
 * writes into a jar. The whole file is a secret because it holds the auth key, so
 * {@link #toString()} redacts it and {@link DeploymentException} messages never carry a value.
 *
 * @param authKey        an OAuth client secret ({@code tskey-client-}) or an auth key ({@code tskey-auth-})
 * @param authKeyExpires informational expiry date of the key; the key is valid through that day
 * @param tag            the tag the helper advertises, {@code tag:[a-z0-9-]+}
 * @param specsPrefix    the specs log line prefix, {@value #DEFAULT_PREFIX} when the file has none
 * @param probeTargets   comma-separated {@code ipv4:port} list for a probe build's isolation test
 */
public record DeploymentSettings(String authKey, Optional<LocalDate> authKeyExpires, String tag,
        String specsPrefix, Optional<String> probeTargets) {

    /** Path of the deployment file inside the jar. */
    public static final String RESOURCE = "cattail/deployment.properties";
    /** Specs log line prefix when the file sets none. */
    public static final String DEFAULT_PREFIX = "CATTAILSPECS";

    static final String AUTHKEY = "authkey";
    static final String AUTHKEY_EXPIRES = "authkey.expires";
    static final String TAG = "tag";
    static final String SPECS_PREFIX = "specs.prefix";
    static final String PROBE_TARGETS = "probe.targets";
    /** Every key the file may contain, in the order of the spec (and of the entry {@code inject} writes). */
    public static final List<String> KEYS = List.of(AUTHKEY, AUTHKEY_EXPIRES, TAG, SPECS_PREFIX, PROBE_TARGETS);

    private static final String CLIENT_PREFIX = "tskey-client-";
    private static final String AUTH_PREFIX = "tskey-auth-";
    /** Appended to a bare OAuth client secret: tsnet then mints an ephemeral, pre-authorized key. */
    private static final String CLIENT_OPTIONS = "?ephemeral=true&preauthorized=true";
    /** The whole {@code authkey} value: a known prefix, then at least one printable non-space ASCII char. */
    private static final Pattern AUTHKEY_PATTERN = Pattern.compile(
            "(?:" + Pattern.quote(CLIENT_PREFIX) + "|" + Pattern.quote(AUTH_PREFIX) + ")[\\x21-\\x7E]+");
    /** An unknown key is echoed in a message only in this shape, see {@link #echoable}. */
    private static final Pattern ECHOABLE_KEY = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final Pattern TAG_PATTERN = Pattern.compile("tag:[a-z0-9-]+");
    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[A-Z0-9_]{1,32}$");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** One probe target: dotted quad without leading zeros, then a port without a leading zero. */
    private static final Pattern IPV4_PORT = Pattern.compile(
            "(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2}):([1-9]\\d{0,4})");

    public DeploymentSettings {
        Objects.requireNonNull(authKey, "authKey");
        Objects.requireNonNull(authKeyExpires, "authKeyExpires");
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(specsPrefix, "specsPrefix");
        Objects.requireNonNull(probeTargets, "probeTargets");
    }

    /**
     * Reads a deployment file: UTF-8, a leading byte order mark is ignored, then
     * {@link #parse(Properties)}. Does not close {@code in}.
     *
     * @throws DeploymentException the file was read but a value breaks a rule
     * @throws IOException         the file could not be read, or is not a valid properties file
     */
    public static DeploymentSettings read(InputStream in) throws DeploymentException, IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        reader.mark(1);
        if (reader.read() != '\uFEFF') {
            reader.reset();
        }
        Properties p = new Properties();
        try {
            p.load(reader);
        } catch (IllegalArgumentException e) {
            // Properties.load reports a malformed unicode escape this way; its message holds no value.
            throw new IOException("deployment file is not a valid properties file", e);
        }
        return parse(p);
    }

    /**
     * Validates {@code p} against the spec's rules. Unknown keys are checked first (the
     * alphabetically first one is reported, as {@code line} when it is not safe to echo, see
     * {@link #echoable}), then each key in {@link #KEYS} order; values are trimmed. Never consults
     * the clock.
     *
     * @throws DeploymentException naming the first offending key and its rule
     */
    public static DeploymentSettings parse(Properties p) throws DeploymentException {
        Optional<String> unknown = p.stringPropertyNames().stream().filter(k -> !KEYS.contains(k)).sorted().findFirst();
        if (unknown.isPresent()) {
            String key = unknown.get();
            if (!echoable(key, value(p, key).orElse(""))) {
                // A secret pasted on a line of its own (or a wrapped half of one) parses as a key.
                throw new DeploymentException("line", "has no key=value form or is not a known key");
            }
            throw new DeploymentException(key, "is not a known key");
        }

        String authKey = value(p, AUTHKEY).orElse("");
        if (!authKey.startsWith(CLIENT_PREFIX) && !authKey.startsWith(AUTH_PREFIX)) {
            throw new DeploymentException(AUTHKEY, "must start with " + CLIENT_PREFIX + " or " + AUTH_PREFIX);
        }
        if (!AUTHKEY_PATTERN.matcher(authKey).matches()) {
            throw new DeploymentException(AUTHKEY, "must be printable ASCII with no whitespace");
        }

        Optional<String> expiresText = value(p, AUTHKEY_EXPIRES);
        Optional<LocalDate> expires = Optional.empty();
        if (expiresText.isPresent()) {
            expires = Optional.of(parseDate(expiresText.get()));
        }

        String tag = value(p, TAG).orElse("");
        if (!TAG_PATTERN.matcher(tag).matches()) {
            throw new DeploymentException(TAG, "must match " + TAG_PATTERN.pattern());
        }

        String prefix = value(p, SPECS_PREFIX).orElse(DEFAULT_PREFIX);
        if (!PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new DeploymentException(SPECS_PREFIX, "must match " + PREFIX_PATTERN.pattern());
        }

        Optional<String> probeTargets = value(p, PROBE_TARGETS);
        if (probeTargets.isPresent() && !validProbeTargets(probeTargets.get())) {
            throw new DeploymentException(PROBE_TARGETS, "must be a comma-separated list of ipv4:port");
        }

        return new DeploymentSettings(authKey, expires, tag, prefix, probeTargets);
    }

    /**
     * The key as the helper gets it in {@code TS_AUTHKEY}: a {@code tskey-client-} secret without a
     * {@code ?} gets {@code ?ephemeral=true&preauthorized=true}; every other value is returned
     * unchanged (tsnet passes an auth key to the control plane as is).
     */
    public String authKeyForHelper() {
        if (authKey.startsWith(CLIENT_PREFIX) && !authKey.contains("?")) {
            return authKey + CLIENT_OPTIONS;
        }
        return authKey;
    }

    /** True when {@code authkey.expires} is set and {@code today} is after it (valid through its last day). */
    public boolean expired(LocalDate today) {
        return authKeyExpires.isPresent() && today.isAfter(authKeyExpires.get());
    }

    /** Days from {@code today} to {@code authkey.expires}: 0 on the last day, negative once past; empty when unset. */
    public OptionalLong daysUntilExpiry(LocalDate today) {
        if (authKeyExpires.isEmpty()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(ChronoUnit.DAYS.between(today, authKeyExpires.get()));
    }

    /**
     * The settings as file entries: {@code authkey} and {@code tag} always, the optional keys only
     * when set ({@code specs.prefix} only when it is not {@value #DEFAULT_PREFIX}, which is what
     * an absent key means). Values are the trimmed ones {@link #parse} accepted.
     */
    public Properties toProperties() {
        Properties p = new Properties();
        p.setProperty(AUTHKEY, authKey);
        authKeyExpires.ifPresent(d -> p.setProperty(AUTHKEY_EXPIRES, d.toString()));
        p.setProperty(TAG, tag);
        if (!specsPrefix.equals(DEFAULT_PREFIX)) {
            p.setProperty(SPECS_PREFIX, specsPrefix);
        }
        probeTargets.ifPresent(t -> p.setProperty(PROBE_TARGETS, t));
        return p;
    }

    @Override
    public String toString() {
        return "DeploymentSettings[authKey=<redacted>, authKeyExpires=" + authKeyExpires + ", tag=" + tag
                + ", specsPrefix=" + specsPrefix + ", probeTargets=" + probeTargets + "]";
    }

    /**
     * Whether an unknown key may appear in a message: it looks like a key name and its line had a
     * value. A line without a separator parses as a key with an empty value, so a pasted secret or
     * the wrapped second half of one is never echoed, whatever its shape.
     */
    private static boolean echoable(String key, String value) {
        return !value.isEmpty() && ECHOABLE_KEY.matcher(key).matches() && !key.startsWith("tskey");
    }

    /** The trimmed value of {@code key}, empty when the key is absent. */
    private static Optional<String> value(Properties p, String key) {
        return Optional.ofNullable(p.getProperty(key)).map(String::trim);
    }

    private static LocalDate parseDate(String text) throws DeploymentException {
        if (DATE_PATTERN.matcher(text).matches()) {
            try {
                return LocalDate.parse(text); // ISO_LOCAL_DATE, strict: 2026-02-30 is rejected
            } catch (DateTimeParseException e) {
                // fall through to the rule
            }
        }
        throw new DeploymentException(AUTHKEY_EXPIRES, "must be a date YYYY-MM-DD");
    }

    private static boolean validProbeTargets(String list) {
        for (String target : list.split(",", -1)) {
            Matcher m = IPV4_PORT.matcher(target);
            if (!m.matches()) {
                return false;
            }
            for (int i = 1; i <= 4; i++) {
                if (Integer.parseInt(m.group(i)) > 255) {
                    return false;
                }
            }
            if (Integer.parseInt(m.group(5)) > 65535) {
                return false;
            }
        }
        return true;
    }
}
