package net.kuching.cattail.text;

/**
 * Untrusted text (a config file, the network, the OS) made safe to show or log on one line: no
 * line breaks, no reordering, no hidden characters, cut without splitting a character.
 */
public final class SafeText {

    private SafeText() {
    }

    /**
     * {@code s} with every control character (ISO controls, the line and paragraph separators) and
     * every format character (Unicode category Cf, such as the bidirectional overrides and isolates
     * U+202E and U+2066 to U+2069, the zero-width characters U+200B to U+200F and U+FEFF) turned
     * into a space; null gives the empty string.
     */
    public static String withoutControls(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            boolean unsafe = Character.isISOControl(cp) || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.FORMAT;
            b.appendCodePoint(unsafe ? ' ' : cp);
        });
        return b.toString();
    }

    /** {@code s} cut to at most {@code max} chars, never inside a surrogate pair; null gives the empty string. */
    public static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        int end = max;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
