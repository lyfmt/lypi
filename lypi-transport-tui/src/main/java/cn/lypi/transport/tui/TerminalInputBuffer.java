package cn.lypi.transport.tui;

import java.util.ArrayList;
import java.util.List;

final class TerminalInputBuffer {
    private static final char ESC = '\033';
    private static final String BRACKETED_PASTE_START = "\033[200~";
    private static final String BRACKETED_PASTE_END = "\033[201~";

    private final StringBuilder buffer = new StringBuilder();
    private final StringBuilder pasteBuffer = new StringBuilder();
    private boolean pasteMode;

    List<TerminalInputSegment> accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }

        List<TerminalInputSegment> segments = new ArrayList<>();
        if (pasteMode) {
            appendPaste(chunk, segments);
            processBufferedInput(segments);
            return segments;
        }

        buffer.append(chunk);
        processBufferedInput(segments);
        return List.copyOf(segments);
    }

    List<TerminalInputSegment> flush() {
        List<TerminalInputSegment> segments = new ArrayList<>();
        if (pasteMode) {
            buffer.insert(0, BRACKETED_PASTE_START + pasteBuffer);
            pasteBuffer.setLength(0);
            pasteMode = false;
        }
        if (!buffer.isEmpty()) {
            segments.add(TerminalInputSegment.key(buffer.toString()));
            buffer.setLength(0);
        }
        return List.copyOf(segments);
    }

    private void processBufferedInput(List<TerminalInputSegment> segments) {
        while (!buffer.isEmpty()) {
            if (startsWith(buffer, BRACKETED_PASTE_START)) {
                buffer.delete(0, BRACKETED_PASTE_START.length());
                pasteMode = true;
                appendPasteFromBuffer(segments);
                continue;
            }

            char first = buffer.charAt(0);
            if (first == ESC) {
                int sequenceLength = completeEscapeSequenceLength(buffer);
                if (sequenceLength == 0) {
                    return;
                }
                segments.add(TerminalInputSegment.key(buffer.substring(0, sequenceLength)));
                buffer.delete(0, sequenceLength);
                continue;
            }

            if (isPlainText(first)) {
                int end = 1;
                while (end < buffer.length() && buffer.charAt(end) != ESC && isPlainText(buffer.charAt(end))) {
                    end++;
                }
                segments.add(TerminalInputSegment.text(buffer.substring(0, end)));
                buffer.delete(0, end);
                continue;
            }

            segments.add(TerminalInputSegment.key(String.valueOf(first)));
            buffer.deleteCharAt(0);
        }
    }

    private void appendPaste(String chunk, List<TerminalInputSegment> segments) {
        pasteBuffer.append(chunk);
        finishPasteIfPossible(segments);
    }

    private void appendPasteFromBuffer(List<TerminalInputSegment> segments) {
        pasteBuffer.append(buffer);
        buffer.setLength(0);
        finishPasteIfPossible(segments);
    }

    private void finishPasteIfPossible(List<TerminalInputSegment> segments) {
        int end = pasteBuffer.indexOf(BRACKETED_PASTE_END);
        if (end < 0) {
            return;
        }

        String pasted = pasteBuffer.substring(0, end);
        String remaining = pasteBuffer.substring(end + BRACKETED_PASTE_END.length());
        pasteBuffer.setLength(0);
        pasteMode = false;
        segments.add(TerminalInputSegment.paste(pasted));

        if (!remaining.isEmpty()) {
            buffer.append(remaining);
        }
    }

    private int completeEscapeSequenceLength(CharSequence value) {
        if (value.length() < 2) {
            return 0;
        }

        char afterEsc = value.charAt(1);
        return switch (afterEsc) {
            case '[' -> completeCsiSequenceLength(value);
            case ']' -> completeTerminatedSequenceLength(value, true);
            case 'P', '_' -> completeTerminatedSequenceLength(value, false);
            case 'O' -> value.length() >= 3 ? 3 : 0;
            default -> 2;
        };
    }

    private int completeCsiSequenceLength(CharSequence value) {
        if (value.length() < 3) {
            return 0;
        }
        if (startsWith(value, "\033[M")) {
            return value.length() >= 6 ? 6 : 0;
        }

        String payload = value.subSequence(2, value.length()).toString();
        for (int index = 0; index < payload.length(); index++) {
            char current = payload.charAt(index);
            if (current < 0x40 || current > 0x7E) {
                continue;
            }
            String candidate = payload.substring(0, index + 1);
            if (candidate.startsWith("<") && (current == 'M' || current == 'm')) {
                return candidate.matches("<\\d+;\\d+;\\d+[Mm]") ? index + 3 : 0;
            }
            return index + 3;
        }
        return 0;
    }

    private int completeTerminatedSequenceLength(CharSequence value, boolean allowBell) {
        for (int index = 2; index < value.length(); index++) {
            if (allowBell && value.charAt(index) == '\007') {
                return index + 1;
            }
            if (value.charAt(index) == ESC && index + 1 < value.length() && value.charAt(index + 1) == '\\') {
                return index + 2;
            }
        }
        return 0;
    }

    private boolean isPlainText(char value) {
        return value >= 0x20 && value != 0x7F;
    }

    private boolean startsWith(CharSequence value, String prefix) {
        if (value.length() < prefix.length()) {
            return false;
        }
        for (int index = 0; index < prefix.length(); index++) {
            if (value.charAt(index) != prefix.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}

record TerminalInputSegment(TerminalInputSegmentKind kind, String value) {
    static TerminalInputSegment text(String value) {
        return new TerminalInputSegment(TerminalInputSegmentKind.TEXT, value);
    }

    static TerminalInputSegment paste(String value) {
        return new TerminalInputSegment(TerminalInputSegmentKind.PASTE, value);
    }

    static TerminalInputSegment key(String value) {
        return new TerminalInputSegment(TerminalInputSegmentKind.KEY_SEQUENCE, value);
    }
}

enum TerminalInputSegmentKind {
    TEXT,
    PASTE,
    KEY_SEQUENCE
}
