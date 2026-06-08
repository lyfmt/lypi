package cn.lypi.agent.compact;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.model.AssistantError;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CompactionPromptTooLongRetry {
    static final int MAX_PTL_RETRIES = 3;
    static final String PTL_RETRY_MARKER = "[earlier conversation truncated for compaction retry]";

    private static final Pattern TOKEN_GAP_PATTERN = Pattern.compile(
        "(?i)(?:by|exceeds?\\s+(?:limit\\s+)?by|over\\s+limit\\s+by)\\s+([0-9][0-9,]*)\\s+tokens?"
    );

    private CompactionPromptTooLongRetry() {
    }

    static Optional<List<AgentMessage>> truncateHeadForRetry(List<AgentMessage> messages, RuntimeException exception) {
        return truncateHeadForRetry(messages, message(exception));
    }

    static Optional<List<AgentMessage>> truncateHeadForRetry(List<AgentMessage> messages, AssistantError error) {
        String text = error == null ? "" : error.errorId() + " " + error.message();
        return truncateHeadForRetry(messages, text);
    }

    static Optional<List<AgentMessage>> truncateHeadForRetry(List<AgentMessage> messages, String promptTooLongText) {
        List<AgentMessage> input = stripRetryMarker(messages);
        List<CompactionApiRound> groups = CompactionApiRoundGrouper.groupMessages(input);
        if (groups.size() < 2) {
            return Optional.empty();
        }

        int dropCount = dropCount(groups, promptTooLongText);
        dropCount = Math.min(dropCount, groups.size() - 1);
        if (dropCount < 1) {
            return Optional.empty();
        }

        List<AgentMessage> sliced = new ArrayList<>();
        for (int index = dropCount; index < groups.size(); index++) {
            sliced.addAll(groups.get(index).messages());
        }
        if (sliced.isEmpty()) {
            return Optional.empty();
        }
        if (needsRetryMarker(sliced.getFirst())) {
            sliced.addFirst(retryMarkerMessage());
        }
        return Optional.of(List.copyOf(sliced));
    }

    private static int dropCount(List<CompactionApiRound> groups, String promptTooLongText) {
        Optional<Integer> tokenGap = tokenGap(promptTooLongText);
        if (tokenGap.isEmpty()) {
            return Math.max(1, groups.size() / 5);
        }

        int accumulated = 0;
        int dropCount = 0;
        for (CompactionApiRound group : groups) {
            accumulated += estimateMessages(group.messages());
            dropCount++;
            if (accumulated >= tokenGap.orElseThrow()) {
                break;
            }
        }
        return dropCount;
    }

    private static Optional<Integer> tokenGap(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = TOKEN_GAP_PATTERN.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(matcher.group(1).replace(",", "")));
    }

    private static boolean needsRetryMarker(AgentMessage message) {
        return message.role() == MessageRole.ASSISTANT || message.role() == MessageRole.TOOL_RESULT;
    }

    private static List<AgentMessage> stripRetryMarker(List<AgentMessage> messages) {
        if (messages.isEmpty() || !isRetryMarker(messages.getFirst())) {
            return List.copyOf(messages);
        }
        return List.copyOf(messages.subList(1, messages.size()));
    }

    private static boolean isRetryMarker(AgentMessage message) {
        if (message.role() != MessageRole.USER || message.content().isEmpty()) {
            return false;
        }
        ContentBlock block = message.content().getFirst();
        Map<String, Object> metadata = block.metadata();
        return PTL_RETRY_MARKER.equals(block.text())
            || metadata != null && Boolean.TRUE.equals(metadata.get("compactRetryMarker"));
    }

    private static AgentMessage retryMarkerMessage() {
        return new AgentMessage(
            "compact-ptl-retry-marker",
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new TextContentBlock(PTL_RETRY_MARKER, Map.of("compactRetryMarker", true))),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static int estimateMessages(List<AgentMessage> messages) {
        return messages.stream()
            .flatMap(message -> message.content().stream())
            .mapToInt(CompactionPromptTooLongRetry::estimateBlock)
            .sum();
    }

    private static int estimateBlock(ContentBlock block) {
        int textTokens = estimateText(block.text());
        Map<String, Object> metadata = block.metadata();
        int metadataTokens = metadata == null || metadata.isEmpty() ? 0 : estimateText(String.valueOf(metadata));
        return Math.max(1, textTokens + metadataTokens);
    }

    private static int estimateText(String text) {
        String safeText = text == null ? "" : text;
        return safeText.length() / 4;
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
