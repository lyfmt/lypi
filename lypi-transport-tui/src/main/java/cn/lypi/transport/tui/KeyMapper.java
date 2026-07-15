package cn.lypi.transport.tui;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class KeyMapper {
    private static final Pattern SGR_MOUSE = Pattern.compile("\\033\\[<(\\d+);\\d+;\\d+([Mm])");

    Optional<TerminalKey> map(String sequence) {
        return switch (sequence) {
            case "\u0003" -> Optional.of(TerminalKey.CTRL_C);
            case "\u000f" -> Optional.of(TerminalKey.CTRL_O);
            case "\u0015" -> Optional.of(TerminalKey.CTRL_U);
            case "\u0019" -> Optional.of(TerminalKey.CTRL_Y);
            case "\u001a" -> Optional.of(TerminalKey.CTRL_Z);
            case "\t" -> Optional.of(TerminalKey.TAB);
            case "\033" -> Optional.of(TerminalKey.ESC);
            case "\r", "\n" -> Optional.of(TerminalKey.ENTER);
            case "\u007f", "\b" -> Optional.of(TerminalKey.BACKSPACE);
            case "\033\u007f" -> Optional.of(TerminalKey.ALT_BACKSPACE);
            case "\033y" -> Optional.of(TerminalKey.ALT_Y);
            case "\033[3;3~" -> Optional.of(TerminalKey.ALT_DELETE);
            case "\033[13;5u", "\033[13;3u" -> Optional.of(TerminalKey.MODIFIED_ENTER);
            case "\033[D", "\033OD" -> Optional.of(TerminalKey.LEFT);
            case "\033[C", "\033OC" -> Optional.of(TerminalKey.RIGHT);
            case "\033[1;5D" -> Optional.of(TerminalKey.WORD_LEFT);
            case "\033[1;5C" -> Optional.of(TerminalKey.WORD_RIGHT);
            case "\033[A", "\033OA" -> Optional.of(TerminalKey.UP);
            case "\033[B", "\033OB" -> Optional.of(TerminalKey.DOWN);
            case "\033[5~" -> Optional.of(TerminalKey.PAGE_UP);
            case "\033[6~" -> Optional.of(TerminalKey.PAGE_DOWN);
            case "\033[?u", "\033[65;129u", "\033[27;7;65u" -> Optional.empty();
            default -> mapSgrMouse(sequence).or(() -> filterKittyReleaseOrRepeat(sequence));
        };
    }

    private Optional<TerminalKey> mapSgrMouse(String sequence) {
        Matcher matcher = SGR_MOUSE.matcher(sequence);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        if (!"M".equals(matcher.group(2))) {
            return Optional.of(TerminalKey.OTHER);
        }
        try {
            int button = Integer.parseInt(matcher.group(1));
            if ((button & 64) == 0) {
                return Optional.of(TerminalKey.OTHER);
            }
            return Optional.of((button & 1) == 0
                ? TerminalKey.MOUSE_WHEEL_UP
                : TerminalKey.MOUSE_WHEEL_DOWN);
        } catch (NumberFormatException ignored) {
            return Optional.of(TerminalKey.OTHER);
        }
    }

    private Optional<TerminalKey> filterKittyReleaseOrRepeat(String sequence) {
        if (sequence != null && sequence.matches("\\033\\[[0-9]+;(129|130)u")) {
            return Optional.empty();
        }
        if (sequence != null && sequence.matches("\\033\\[27;[0-9]+;[0-9]+u")) {
            return Optional.empty();
        }
        return Optional.of(TerminalKey.OTHER);
    }
}
