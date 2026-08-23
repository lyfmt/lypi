package cn.lycode.ai.provider.openai;

import cn.lycode.contracts.model.AssistantStreamEvent;
import cn.lycode.contracts.model.TextDelta;
import cn.lycode.contracts.model.ThinkingDelta;
import java.util.ArrayList;
import java.util.List;

public final class TaggedThinkingContentParser {
    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    private final StringBuilder pending = new StringBuilder();
    private Mode mode = Mode.TEXT;

    public List<AssistantStreamEvent> accept(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        pending.append(content);
        List<AssistantStreamEvent> events = new ArrayList<>();
        while (!pending.isEmpty()) {
            String expectedTag = mode == Mode.TEXT ? OPEN_TAG : CLOSE_TAG;
            int tagIndex = pending.indexOf(expectedTag);
            if (tagIndex >= 0) {
                emit(events, pending.substring(0, tagIndex));
                pending.delete(0, tagIndex + expectedTag.length());
                mode = mode == Mode.TEXT ? Mode.THINKING : Mode.TEXT;
                continue;
            }

            int retainedLength = longestTagPrefixAtEnd(expectedTag);
            int safeLength = pending.length() - retainedLength;
            if (safeLength > 0) {
                emit(events, pending.substring(0, safeLength));
                pending.delete(0, safeLength);
            }
            break;
        }
        return List.copyOf(events);
    }

    public List<AssistantStreamEvent> finish() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<AssistantStreamEvent> events = new ArrayList<>();
        emit(events, pending.toString());
        pending.setLength(0);
        return List.copyOf(events);
    }

    private int longestTagPrefixAtEnd(String tag) {
        int maximum = Math.min(pending.length(), tag.length() - 1);
        for (int length = maximum; length > 0; length--) {
            int pendingStart = pending.length() - length;
            boolean matches = true;
            for (int index = 0; index < length; index++) {
                if (pending.charAt(pendingStart + index) != tag.charAt(index)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return length;
            }
        }
        return 0;
    }

    private void emit(List<AssistantStreamEvent> events, String text) {
        if (text.isEmpty()) {
            return;
        }
        if (mode == Mode.TEXT) {
            if (!events.isEmpty() && events.getLast() instanceof TextDelta previous) {
                events.set(events.size() - 1, new TextDelta(previous.text() + text));
            } else {
                events.add(new TextDelta(text));
            }
            return;
        }
        if (!events.isEmpty() && events.getLast() instanceof ThinkingDelta previous) {
            events.set(events.size() - 1, new ThinkingDelta(previous.text() + text));
        } else {
            events.add(new ThinkingDelta(text));
        }
    }

    private enum Mode {
        TEXT,
        THINKING
    }
}
