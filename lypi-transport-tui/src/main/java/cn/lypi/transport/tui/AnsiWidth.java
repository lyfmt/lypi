package cn.lypi.transport.tui;

final class AnsiWidth {
    private AnsiWidth() {
    }

    static int displayWidth(String value) {
        int width = 0;
        for (int index = 0; index < value.length();) {
            int escapeEnd = escapeEnd(value, index);
            if (escapeEnd > index) {
                index = escapeEnd;
                continue;
            }
            int codePoint = value.codePointAt(index);
            if (codePoint == 0x200D) {
                index += Character.charCount(codePoint);
                if (index < value.length()) {
                    index += Character.charCount(value.codePointAt(index));
                }
                continue;
            }
            if (isZeroWidthCodePoint(codePoint)) {
                index += Character.charCount(codePoint);
                continue;
            }
            width += codePointWidth(codePoint);
            index += Character.charCount(codePoint);
        }
        return width;
    }

    static String truncate(String value, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (displayWidth(value) <= maxWidth) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        int width = 0;
        int contentLimit = Math.max(0, maxWidth - 1);
        boolean emittedAnsi = false;
        boolean resetAnsi = false;
        for (int index = 0; index < value.length();) {
            int escapeEnd = escapeEnd(value, index);
            if (escapeEnd > index) {
                String escape = value.substring(index, escapeEnd);
                result.append(escape);
                emittedAnsi = true;
                resetAnsi = "\033[0m".equals(escape);
                index = escapeEnd;
                continue;
            }
            int codePoint = value.codePointAt(index);
            int codePointWidth = isZeroWidthCodePoint(codePoint) ? 0 : codePointWidth(codePoint);
            if (width + codePointWidth > contentLimit) {
                break;
            }
            result.appendCodePoint(codePoint);
            width += codePointWidth;
            index += Character.charCount(codePoint);
        }
        if (emittedAnsi && !resetAnsi) {
            result.append("\033[0m");
        }
        result.append("…");
        return result.toString();
    }

    private static int escapeEnd(String value, int index) {
        if (value.charAt(index) != '\033' || index + 1 >= value.length()) {
            return index;
        }
        char kind = value.charAt(index + 1);
        if (kind == '[') {
            int cursor = index + 2;
            while (cursor < value.length()) {
                char current = value.charAt(cursor++);
                if (current >= 0x40 && current <= 0x7E) {
                    return cursor;
                }
            }
            return value.length();
        }
        if (kind == ']' || kind == '_' || kind == 'P' || kind == '^') {
            int cursor = index + 2;
            while (cursor < value.length()) {
                char current = value.charAt(cursor);
                if (current == '\007') {
                    return cursor + 1;
                }
                if (current == '\033' && cursor + 1 < value.length() && value.charAt(cursor + 1) == '\\') {
                    return cursor + 2;
                }
                cursor++;
            }
            return value.length();
        }
        return index + 2;
    }

    private static boolean isZeroWidthCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
            || type == Character.ENCLOSING_MARK
            || type == Character.FORMAT
            || codePoint == 0xFE0F;
    }

    private static int codePointWidth(int codePoint) {
        if (Character.isISOControl(codePoint)) {
            return 0;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            || block == Character.UnicodeBlock.HIRAGANA
            || block == Character.UnicodeBlock.KATAKANA
            || block == Character.UnicodeBlock.HANGUL_SYLLABLES) {
            return 2;
        }
        if (codePoint >= 0x1F300 && codePoint <= 0x1FAFF) {
            return 2;
        }
        return 1;
    }
}
