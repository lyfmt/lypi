package cn.lypi.transport.tui;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiErrorBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TuiTranscriptProjector {
    private static final int RESULT_MAX_CODE_POINTS = 200;
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    List<TuiBlock> project(List<AgentMessage> transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return List.of();
        }
        List<TuiBlock> projected = new ArrayList<>();
        Map<String, Integer> toolIndexes = new LinkedHashMap<>();
        for (AgentMessage message : transcript) {
            if (message == null || message.content() == null) {
                continue;
            }
            for (int index = 0; index < message.content().size(); index++) {
                ContentBlock block = message.content().get(index);
                if (block == null) {
                    continue;
                }
                String sourceBlockId = sourceBlockId(message.id(), block, index);
                switch (block.kind()) {
                    case TEXT -> projected.add(new TuiMessageBlock(
                        sourceBlockId,
                        message.id(),
                        roleName(message.role()),
                        block.text(),
                        false
                    ));
                    case THINKING -> projected.add(new TuiThinkingBlock(
                        sourceBlockId,
                        message.id(),
                        block.text(),
                        false,
                        false
                    ));
                    case ERROR -> projected.add(new TuiErrorBlock(sourceBlockId, block.text()));
                    case TOOL_CALL -> projectToolCall(projected, toolIndexes, message.id(), block, sourceBlockId);
                    case TOOL_RESULT -> completeToolBlock(
                        projected,
                        toolIndexes,
                        message.id(),
                        block,
                        sourceBlockId
                    );
                    default -> {
                    }
                }
            }
        }
        return List.copyOf(projected);
    }

    private void projectToolCall(
        List<TuiBlock> projected,
        Map<String, Integer> toolIndexes,
        String messageId,
        ContentBlock block,
        String sourceBlockId
    ) {
        String typedToolUseId = block instanceof ToolCallContentBlock toolCall ? toolCall.toolUseId() : "";
        String toolUseId = firstNonBlank(
            typedToolUseId,
            metadataString(block.metadata(), "toolUseId"),
            sourceBlockId
        );
        if (toolIndexes.containsKey(toolUseId)) {
            return;
        }
        String typedToolName = block instanceof ToolCallContentBlock toolCall ? toolCall.toolName() : "";
        String toolName = firstNonBlank(
            typedToolName,
            metadataString(block.metadata(), "toolName"),
            "unknown"
        );
        String label = firstNonBlank(
            metadataString(block.metadata(), "inputSummary"),
            block.text(),
            toolName
        );
        int projectedIndex = projected.size();
        projected.add(new TuiToolBlock(
            "tool:" + toolUseId,
            messageId,
            toolUseId,
            toolName,
            TuiToolState.PENDING,
            label,
            false
        ));
        toolIndexes.put(toolUseId, projectedIndex);
    }

    private void completeToolBlock(
        List<TuiBlock> projected,
        Map<String, Integer> toolIndexes,
        String messageId,
        ContentBlock block,
        String sourceBlockId
    ) {
        String typedToolUseId = block instanceof ToolResultContentBlock result ? result.toolUseId() : "";
        String toolUseId = firstNonBlank(
            typedToolUseId,
            metadataString(block.metadata(), "toolUseId"),
            sourceBlockId
        );
        Integer projectedIndex = toolIndexes.get(toolUseId);
        if (projectedIndex == null) {
            addUnmatchedResult(projected, toolIndexes, messageId, block, toolUseId);
            return;
        }
        TuiBlock current = projected.get(projectedIndex);
        if (!(current instanceof TuiToolBlock tool)) {
            addUnmatchedResult(projected, toolIndexes, messageId, block, toolUseId);
            return;
        }
        projected.set(projectedIndex, new TuiToolBlock(
            tool.blockId(),
            tool.messageId(),
            tool.toolUseId(),
            tool.toolName(),
            stateFor(block),
            tool.label(),
            resultDetails(block),
            false
        ));
    }

    private void addUnmatchedResult(
        List<TuiBlock> projected,
        Map<String, Integer> toolIndexes,
        String messageId,
        ContentBlock result,
        String toolUseId
    ) {
        String toolName = firstNonBlank(metadataString(result.metadata(), "toolName"), "unknown");
        String label = "unknown".equals(toolName) ? toolUseId : toolName;
        int projectedIndex = projected.size();
        projected.add(new TuiToolBlock(
            "tool:" + toolUseId,
            messageId,
            toolUseId,
            toolName,
            stateFor(result),
            label,
            resultDetails(result),
            false
        ));
        toolIndexes.put(toolUseId, projectedIndex);
    }

    static TuiToolState stateFor(ToolResultContentBlock result) {
        return stateFor(result.error(), result.metadata());
    }

    private static TuiToolState stateFor(ContentBlock result) {
        if (result instanceof ToolResultContentBlock typedResult) {
            return stateFor(typedResult);
        }
        return stateFor(metadataBoolean(result.metadata(), "error"), result.metadata());
    }

    private static TuiToolState stateFor(boolean error, Map<String, Object> metadata) {
        String status = metadataString(metadata, "status").trim().toUpperCase(Locale.ROOT);
        if ("CANCELLED".equals(status)) {
            return TuiToolState.CANCELLED;
        }
        if ("FAILED".equals(status) || "TIMED_OUT".equals(status)) {
            return TuiToolState.FAILED;
        }
        return error ? TuiToolState.FAILED : TuiToolState.DONE;
    }

    static String resultDetails(ToolResultContentBlock result) {
        return resultSummary(result == null ? "" : result.text());
    }

    private static String resultDetails(ContentBlock result) {
        return resultSummary(result == null ? "" : result.text());
    }

    static String resultSummary(String text) {
        String normalized = normalizeSingleLine(text);
        int hiddenLines = lineBreakCount(text);
        if (hiddenLines == 0) {
            return truncate(normalized, RESULT_MAX_CODE_POINTS);
        }
        String suffix = " (+" + hiddenLines + " lines)";
        if (codePointCount(normalized) + codePointCount(suffix) <= RESULT_MAX_CODE_POINTS) {
            return normalized + suffix;
        }
        int available = RESULT_MAX_CODE_POINTS - codePointCount(suffix) - 1;
        if (available <= 0) {
            return truncate(suffix.strip(), RESULT_MAX_CODE_POINTS);
        }
        return prefix(normalized, available) + "…" + suffix;
    }

    private static String sourceBlockId(String messageId, ContentBlock block, int index) {
        return valueOrEmpty(messageId) + ":" + block.kind().name().toLowerCase(Locale.ROOT) + ":" + index;
    }

    private static String roleName(MessageRole role) {
        if (role == MessageRole.USER) {
            return "user";
        }
        if (role == MessageRole.SYSTEM_LOCAL) {
            return "system";
        }
        if (role == MessageRole.TOOL_RESULT) {
            return "tool";
        }
        return "assistant";
    }

    private static String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return "";
        }
        return metadata.get(key).toString();
    }

    private static boolean metadataBoolean(Map<String, Object> metadata, String key) {
        if (metadata == null || metadata.get(key) == null) {
            return false;
        }
        Object value = metadata.get(key);
        return value instanceof Boolean booleanValue
            ? booleanValue
            : Boolean.parseBoolean(value.toString());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String normalizeSingleLine(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)
                || Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)) {
                pendingSpace = !normalized.isEmpty();
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        return normalized.toString();
    }

    private static String truncate(String value, int maxCodePoints) {
        if (value == null || maxCodePoints <= 0) {
            return "";
        }
        if (codePointCount(value) <= maxCodePoints) {
            return value;
        }
        if (maxCodePoints == 1) {
            return "…";
        }
        return prefix(value, maxCodePoints - 1) + "…";
    }

    private static String prefix(String value, int codePoints) {
        if (value == null || value.isEmpty() || codePoints <= 0) {
            return "";
        }
        int count = Math.min(codePoints, codePointCount(value));
        return value.substring(0, value.offsetByCodePoints(0, count));
    }

    private static int lineBreakCount(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = LINE_BREAK.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int codePointCount(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
