package cn.lypi.agent;

import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.MessageBlockSnapshot;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.RetryEndEvent;
import cn.lypi.contracts.event.RetryStartEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.model.ProviderRetryNotice;
import cn.lypi.contracts.model.ToolCallDelta;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class TurnEventPublisher {
    private final EventBus eventBus;
    private final Clock clock;

    TurnEventPublisher(EventBus eventBus, Clock clock) {
        this.eventBus = eventBus;
        this.clock = clock;
    }

    void publishTurnEnd(String sessionId, String turnId, TurnStatus status, Instant startedAt, int toolRound) {
        Instant endedAt = clock.instant();
        long durationMillis = Math.max(0L, Duration.between(startedAt, endedAt).toMillis());
        eventBus.publish(new TurnEndEvent(
            sessionId,
            turnId,
            status.name(),
            startedAt,
            endedAt,
            durationMillis,
            toolRound,
            endedAt
        ));
    }

    void publishRetryStart(String sessionId, ProviderRetryNotice notice) {
        eventBus.publish(new RetryStartEvent(
            sessionId,
            notice.attempt(),
            notice.retryableErrorId(),
            clock.instant()
        ));
    }

    void publishRetryEnd(String sessionId, ProviderRetryNotice notice, boolean success) {
        eventBus.publish(new RetryEndEvent(
            sessionId,
            notice.attempt(),
            success,
            clock.instant()
        ));
    }

    void publishAssistantMessageStart(String sessionId, String messageId, MessageKind kind) {
        eventBus.publish(new MessageStartEvent(
            sessionId,
            messageId,
            cn.lypi.contracts.context.MessageRole.ASSISTANT,
            kind,
            Map.of(
                "streaming", true,
                "kindProvisional", true,
                "finalKindSource", "message_end"
            ),
            clock.instant()
        ));
    }

    void publishMessageStart(String sessionId, AgentMessage message) {
        eventBus.publish(new MessageStartEvent(
            sessionId,
            message.id(),
            message.role(),
            message.kind(),
            Map.of(),
            clock.instant()
        ));
    }

    void publishMessageEnd(String sessionId, AgentMessage message) {
        eventBus.publish(new MessageEndEvent(
            sessionId,
            message.id(),
            message.role(),
            message.kind(),
            blockSnapshots(message),
            message.usage(),
            message.stopReason(),
            Map.of(),
            clock.instant()
        ));
    }

    void publishAssistantMessageEnd(String sessionId, String messageId) {
        publishAssistantMessageEnd(sessionId, messageId, MessageKind.TEXT);
    }

    void publishAssistantMessageEnd(String sessionId, String messageId, MessageKind kind) {
        eventBus.publish(new MessageEndEvent(
            sessionId,
            messageId,
            cn.lypi.contracts.context.MessageRole.ASSISTANT,
            kind,
            List.of(),
            Optional.empty(),
            Optional.of("error"),
            Map.of("streaming", true),
            clock.instant()
        ));
    }

    void publishAssistantDelta(MessageDeltaEvent delta) {
        eventBus.publish(delta);
    }

    MessageDeltaEvent assistantDelta(
        String sessionId,
        String messageId,
        MessageKind kind,
        String blockId,
        ContentBlockKind blockKind,
        String delta,
        boolean isFinal,
        Map<String, Object> metadata
    ) {
        return new MessageDeltaEvent(
            sessionId,
            messageId,
            cn.lypi.contracts.context.MessageRole.ASSISTANT,
            kind,
            blockId,
            blockKind,
            delta,
            isFinal,
            metadata,
            clock.instant()
        );
    }

    Map<String, Object> toolCallDeltaMetadata(ToolCallDelta delta) {
        Map<String, Object> partialInput = immutableNullableMap(delta.partialInput());
        return Map.of(
            "toolUseId", delta.toolUseId(),
            "toolName", delta.toolName(),
            "partialInput", partialInput,
            "complete", delta.complete(),
            "inputSummary", toolCallInputSummary(delta.toolName(), partialInput)
        );
    }

    private List<MessageBlockSnapshot> blockSnapshots(AgentMessage message) {
        if (message.content() == null || message.content().isEmpty()) {
            return List.of();
        }
        List<MessageBlockSnapshot> snapshots = new ArrayList<>();
        for (int index = 0; index < message.content().size(); index++) {
            ContentBlock block = message.content().get(index);
            snapshots.add(new MessageBlockSnapshot(
                blockId(message.id(), block.kind(), index),
                block.kind(),
                block.text(),
                snapshotMetadata(block)
            ));
        }
        return List.copyOf(snapshots);
    }

    private Map<String, Object> snapshotMetadata(ContentBlock block) {
        if (!(block instanceof ToolCallContentBlock toolCall)) {
            return block.metadata();
        }
        Map<String, Object> metadata = new LinkedHashMap<>(block.metadata());
        Map<String, Object> input = immutableNullableMap(asMap(metadata.get("input")));
        metadata.put("toolUseId", toolCall.toolUseId());
        metadata.put("toolName", toolCall.toolName());
        metadata.put("input", input);
        metadata.put("complete", Boolean.TRUE.equals(metadata.get("complete")));
        metadata.put("inputSummary", toolCallInputSummary(toolCall.toolName(), input));
        return Map.copyOf(metadata);
    }

    private String blockId(String messageId, ContentBlockKind kind, int index) {
        return switch (kind) {
            case TEXT -> textBlockId(messageId);
            case THINKING -> thinkingBlockId(messageId);
            case ERROR -> errorBlockId(messageId);
            default -> messageId + ":" + kind.name().toLowerCase() + ":" + index;
        };
    }

    private String toolCallInputSummary(String toolName, Map<String, Object> partialInput) {
        if (partialInput.isEmpty()) {
            return toolName;
        }
        return toolName + " " + partialInput;
    }

    private Map<String, Object> immutableNullableMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> source) || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, mapValue) -> {
            if (key != null) {
                result.put(key.toString(), mapValue);
            }
        });
        return result;
    }

    static String textBlockId(String messageId) {
        return messageId + ":text:0";
    }

    static String thinkingBlockId(String messageId) {
        return messageId + ":thinking:0";
    }

    static String errorBlockId(String messageId) {
        return messageId + ":error:0";
    }

    static String toolCallBlockId(String messageId, String toolUseId) {
        return messageId + ":tool_call:" + toolUseId;
    }
}
