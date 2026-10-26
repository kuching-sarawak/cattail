package net.kuching.cattail.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SafeTextTest {

    @Test
    void controlAndSeparatorCharactersBecomeSpaces() {
        assertEquals("a b c d e f", SafeText.withoutControls("a\nb\tc\u0000d e f"));
        assertEquals("a b", SafeText.withoutControls("a\u0085b"), "C1 controls too");
        assertEquals("", SafeText.withoutControls(null));
        assertEquals("", SafeText.withoutControls(""));
    }

    /** Bidirectional overrides and isolates would reorder what follows them; zero-width ones hide text. */
    @Test
    void formatCharactersBecomeSpaces() {
        assertEquals("evil txt.exe", SafeText.withoutControls("evil‮txt.exe"));
        for (int cp : new int[] {0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200B,
                0x200C, 0x200D, 0x200E, 0x200F, 0xFEFF, 0xE0001}) {
            String in = "a" + Character.toString(cp) + "b";
            assertEquals("a b", SafeText.withoutControls(in), "U+" + Integer.toHexString(cp).toUpperCase());
        }
    }

    @Test
    void visibleTextIsKept() {
        String text = "Voice chat on · 2:00 AM ET é 😀 §a & |";
        assertEquals(text, SafeText.withoutControls(text));
    }

    @Test
    void clipNeverSplitsASurrogatePair() {
        assertEquals("abc", SafeText.clip("abc", 3));
        assertEquals("ab", SafeText.clip("abcd", 2));
        assertEquals("ab", SafeText.clip("ab😀", 3), "the pair is dropped whole, not cut in half");
        assertEquals("ab😀", SafeText.clip("ab😀c", 4));
        assertEquals("", SafeText.clip(null, 5));
        assertEquals("", SafeText.clip("abc", 0));
    }
}
