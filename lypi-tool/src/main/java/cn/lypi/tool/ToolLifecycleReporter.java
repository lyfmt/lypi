package cn.lypi.tool;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolResultSummary;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 负责工具执行生命周期事件及审计摘要。
 */
final class ToolLifecycleReporter {
    private static final String METADATA_ORIGINAL_TOOL_NAME = "originalToolName";
    private static final String METADATA_TURN_ID = "turnId";

    private final ToolExecutionEventPublisher eventPublisher;
    private final ToolEventSummaryFormatter summaryFormatter;

    ToolLifecycleReporter(ToolExecutionEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher == null ? ToolExecutionEventPublisher.noop() : eventPublisher;
        this.summaryFormatter = new ToolEventSummaryFormatter();
    }

    ToolExecutionEventPublisher.StartedToolExecution start(
        ToolUseRequest request,
        ToolUseContext context,
        String toolName,
        String originalToolName,
        String renderedForUser,
        Map<String, Object> input
    ) {
        return eventPublisher.start(
            context.sessionId(),
            request.toolUseId(),
            request.parentMessageId(),
            stringMetadata(context, METADATA_TURN_ID),
            toolName,
            displayTitle(toolName),
            summaryFormatter.inputSummary(toolName, renderedForUser, input),
            inputMetadata(input, toolName, originalToolName)
        );
    }

    void end(
        ToolUseRequest request,
        ToolUseContext context,
        String toolName,
        String originalToolName,
        ToolResult<?> rawResult,
        ToolResult<?> finalResult,
        ToolExecutionStatus status,
        Instant startedAt
    ) {
        Instant endedAt = Instant.now();
        ToolResult<?> eventResult = rawResult == null ? finalResult : rawResult;
        Map<String, Object> metadata = eventMetadata(toolName, originalToolName);
        ToolResultSummary summary = resultSummary(toolName, eventResult, status, metadata);
        ToolOutputRef ref = resultRef(
            context.sessionId(),
            request.toolUseId(),
            toolName,
            eventResult,
            finalResult != null && finalResult.replacement().isPresent()
        );
        eventPublisher.end(
            context.sessionId(),
            request.toolUseId(),
            status,
            summary.exitCode(),
            summary,
            ref,
            startedAt,
            endedAt,
            metadata
        );
    }

    ToolResultSummary resultSummary(
        String toolName,
        ToolResult<?> result,
        ToolExecutionStatus status,
        Map<String, Object> metadata
    ) {
        String outputText = outputText(result);
        boolean error = status != ToolExecutionStatus.SUCCEEDED || result == null || result.isError();
        return new ToolResultSummary(
            toolName + " " + status.name().toLowerCase(),
            summaryFormatter.resultSummary(outputText),
            error,
            exitCode(result),
            status == ToolExecutionStatus.TIMED_OUT,
            byteLength(outputText),
            metadata
        );
    }

    ToolOutputRef resultRef(
        String sessionId,
        String toolUseId,
        String toolName,
        ToolResult<?> result,
        boolean budgeted
    ) {
        if (result == null) {
            return null;
        }
        String outputText = outputText(result);
        if (!budgeted && result.replacement().isEmpty()) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", toolName);
        metadata.put("preview", summaryFormatter.preview(outputText));
        if (budgeted) {
            metadata.put("truncated", true);
            metadata.put("truncationReason", "budgeted");
        }
        return new ToolOutputRef(
            "toolout_" + sessionId + "_" + toolUseId,
            sessionId,
            toolUseId,
            "text/plain; charset=utf-8",
            "pending",
            "",
            sha256(outputText),
            byteLength(outputText),
            metadata
        );
    }

    String outputText(ToolResult<?> result) {
        if (result == null || result.newMessages() == null || result.newMessages().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (AgentMessage message : result.newMessages()) {
            if (message.content() == null) {
                continue;
            }
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolResultContentBlock toolResultBlock) {
                    parts.add(toolResultBlock.text());
                }
            }
        }
        return String.join("\n", parts);
    }

    Map<String, Object> inputMetadata(Map<String, Object> input, String toolName, String originalToolName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (input != null) {
            metadata.putAll(input);
        }
        metadata.putAll(originalToolMetadata(toolName, originalToolName));
        return Collections.unmodifiableMap(metadata);
    }

    Map<String, Object> eventMetadata(String toolName, String originalToolName) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", toolName);
        metadata.putAll(originalToolMetadata(toolName, originalToolName));
        return Map.copyOf(metadata);
    }

    private Map<String, Object> originalToolMetadata(String toolName, String originalToolName) {
        if (Objects.equals(toolName, originalToolName)) {
            return Map.of();
        }
        return Map.of(METADATA_ORIGINAL_TOOL_NAME, originalToolName);
    }

    private String stringMetadata(ToolUseContext context, String key) {
        Object value = context.metadata().get(key);
        return value == null ? null : value.toString();
    }

    private String displayTitle(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "Tool";
        }
        return Character.toUpperCase(toolName.charAt(0)) + toolName.substring(1);
    }

    private long byteLength(String outputText) {
        return outputText == null ? 0L : outputText.getBytes(StandardCharsets.UTF_8).length;
    }

    private String sha256(String outputText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((outputText == null ? "" : outputText).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable.", exception);
        }
    }

    private Integer exitCode(ToolResult<?> result) {
        Object output = result == null ? null : result.output();
        if (output instanceof Map<?, ?> outputMap) {
            Object exitCode = outputMap.get("exitCode");
            if (exitCode instanceof Number number) {
                return number.intValue();
            }
        }
        return null;
    }
}
