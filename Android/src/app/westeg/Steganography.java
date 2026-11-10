package app.westeg;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Steganography {
    static final Pattern TOKEN = Pattern.compile("<0x([0-9A-Fa-f]{1,6})>");
    private static final int FIRST_ASCII = 32;
    private static final int LAST_ASCII = 126;
    private static final int[] CARRIERS = {
            0x200C, 0x200D, 0x2060, 0xFEFF, 0x034F, 0x00AD, 0xE0001, 0xE0020, 0xE0041, 0xE0061
    };
    // Keep in sync with the Windows codec. Printable ASCII pairs never contain this sequence.
    private static final String COVER_BOUNDARY = "999991999991";
    private static final String LEGACY_COVER_BOUNDARY = "999990999990";

    private Steganography() { }

    static final class Encoded {
        final String text;
        final int injected;

        Encoded(String text, int injected) {
            this.text = text;
            this.injected = injected;
        }
    }

    static final class Counts {
        int total;
        int carriers;
    }

    static Encoded encodeMessage(String input) {
        StringBuilder hidden = new StringBuilder();
        String cover = "";
        for (int i = 0; i < input.length(); i++) {
            char value = input.charAt(i);
            if (value == '\\') {
                if (i + 1 >= input.length() || input.charAt(i + 1) != '\\') {
                    cover = input.substring(i + 1);
                    break;
                }
                i++;
            }
            hidden.append(value);
        }

        StringBuilder encoded = new StringBuilder();
        int injected = hidden.length() * 2;
        for (int i = 0; i < hidden.length(); i++) {
            int value = hidden.codePointAt(i);
            if (value < FIRST_ASCII || value > LAST_ASCII) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "发现 U+%04X。请输入 ASCII 32–126 范围内的字符。", value));
            }
            int digit = value - FIRST_ASCII;
            encoded.appendCodePoint(CARRIERS[digit / CARRIERS.length]);
            encoded.appendCodePoint(CARRIERS[digit % CARRIERS.length]);
        }
        if (count(restore(cover)).carriers > 0) {
            for (int i = 0; i < COVER_BOUNDARY.length(); i++) {
                encoded.appendCodePoint(CARRIERS[COVER_BOUNDARY.charAt(i) - '0']);
            }
            injected += COVER_BOUNDARY.length();
        }
        return new Encoded(encoded.append(cover).toString(), injected);
    }

    static String decode(String raw) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length();) {
            int value = raw.codePointAt(i);
            int digit = carrierDigit(value);
            if (digit >= 0) digits.append((char) ('0' + digit));
            i += Character.charCount(value);
        }
        int boundary = digits.indexOf(COVER_BOUNDARY);
        int start = 0;
        int end = digits.length();
        if (boundary >= 0 && isAsciiPayload(digits, boundary)) {
            end = boundary;
        } else {
            int legacyBoundary = digits.lastIndexOf(LEGACY_COVER_BOUNDARY);
            if (legacyBoundary >= 0) start = legacyBoundary + LEGACY_COVER_BOUNDARY.length();
        }
        StringBuilder decoded = new StringBuilder((end - start) / 2);
        for (int i = start; i + 1 < end; i += 2) {
            int ascii = decodePair(digits.charAt(i), digits.charAt(i + 1));
            if (ascii <= LAST_ASCII) decoded.append((char) ascii);
        }
        return decoded.toString();
    }

    private static boolean isAsciiPayload(CharSequence digits, int end) {
        if (end % 2 != 0) return false;
        for (int i = 0; i < end; i += 2) {
            if (decodePair(digits.charAt(i), digits.charAt(i + 1)) > LAST_ASCII) return false;
        }
        return true;
    }

    private static int decodePair(char first, char second) {
        return FIRST_ASCII + (first - '0') * CARRIERS.length + second - '0';
    }

    static String restore(String text) {
        Matcher match = TOKEN.matcher(text);
        StringBuilder restored = new StringBuilder();
        int start = 0;
        while (match.find()) {
            int value = Integer.parseInt(match.group(1), 16);
            if (isInvisible(value)) {
                restored.append(text, start, match.start()).appendCodePoint(value);
                start = match.end();
            }
        }
        return restored.append(text, start, text.length()).toString();
    }

    static String render(String text) {
        StringBuilder rendered = new StringBuilder();
        for (int i = 0; i < text.length();) {
            int value = text.codePointAt(i);
            if (isInvisible(value)) rendered.append(marker(value));
            else rendered.appendCodePoint(value);
            i += Character.charCount(value);
        }
        return rendered.toString();
    }

    static Counts count(String raw) {
        Counts counts = new Counts();
        for (int i = 0; i < raw.length();) {
            int value = raw.codePointAt(i);
            if (isInvisible(value)) counts.total++;
            if (carrierDigit(value) >= 0) counts.carriers++;
            i += Character.charCount(value);
        }
        return counts;
    }

    static String marker(int value) {
        return "<0x" + Integer.toHexString(value).toUpperCase(Locale.ROOT) + ">";
    }

    static boolean isInvisible(int value) {
        if (!Character.isValidCodePoint(value) || value == ' ' || value == '\r' || value == '\n' || value == '\t') {
            return false;
        }
        switch (Character.getType(value)) {
            case Character.FORMAT:
            case Character.CONTROL:
            case Character.NON_SPACING_MARK:
            case Character.ENCLOSING_MARK:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
            case Character.SPACE_SEPARATOR:
                return true;
            default:
                return value == 0x115F || value == 0x1160 || value == 0x17B4 || value == 0x17B5
                        || value == 0x2800 || value == 0x3164 || value == 0xFFA0;
        }
    }

    private static int carrierDigit(int value) {
        for (int i = 0; i < CARRIERS.length; i++) {
            if (CARRIERS[i] == value) return i;
        }
        return -1;
    }
}
