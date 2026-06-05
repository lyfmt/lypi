package cn.lypi.agent;

import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.MessageBlockSnapshot;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.ToolCallDelta;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AssistantStreamEventMessageMapper {
    private final String sessionId;
    private final Clock clock;
    private final Instant fixedTimestamp;
    private final LinkedHashMap<String, BlockAccumulator> blocks = new LinkedHashMap<>();
    private final Map<String, String> fallbackToolCallBlockIds = new LinkedHashMap<>();
    private String messageId;
    private int fallbackToolCallIndex;

    public AssistantStreamEventMessageMapper(String sessionId, Clock clock) {
        this.sessionId = sessionId;
        this.clock = clock;
        this.fixedTimestamp = null;
    }

    AssistantStreamEventMessageMapper(String sessionId, Instant fixedTimestamp) {
        this.sessionId = sessionId;
        this.clock = null;
        this.fixedTimestamp = fixedTimestamp;
    }

    public List<AgentEvent> map(AssistantStreamEvent event) {
        return switch (event) {
            case AssistantStart start -> start(start);
            case TextDelta delta -> textDelta(delta);
            case ThinkingDelta delta -> thinkingDelta(delta);
            case ToolCallDelta delta -> toolCallDelta(delta);
            case AssistantDone done -> done(done);
            case AssistantError error -> error(error);
        };
    }

    private List<AgentEvent> start(AssistantStart start) {
        messageId = start.messageId();
        blocks.clear();
        fallbackToolCallBlockIds.clear();
        fallbackToolCallIndex = 0;
        return List.of(new MessageStartEvent(
            sessionId,
            messageId,
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            Map.of(),
            timestamp()
        ));
    }

    private List<AgentEvent> textDelta(TextDelta delta) {
        ensureMessageId();
        String blockId = messageId + ":text:0";
        BlockAccumulator block = block(blockId, ContentBlockKind.TEXT, Map.of());
        block.append(delta.text());
        return List.of(new MessageDeltaEvent(
            sessionId,
            messageId,
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            blockId,
            ContentBlockKind.TEXT,
            delta.text(),
            false,
            Map.of(),
            timestamp()
        ));
    }

    private List<AgentEvent> thinkingDelta(ThinkingDelta delta) {
        ensureMessageId();
        String blockId = messageId + ":thinking:0";
        BlockAccumulator block = block(blockId, ContentBlockKind.THINKING, Map.of());
        block.append(delta.text());
        return List.of(new MessageDeltaEvent(
            sessionId,
            messageId,
            MessageRole.ASSISTANT,
            MessageKind.THINKING,
            blockId,
            ContentBlockKind.THINKING,
            delta.text(),
            false,
            Map.of(),
            timestamp()
        ));
    }

    private List<AgentEvent> toolCallDelta(ToolCallDelta delta) {
        ensureMessageId();
        String blockId = toolCallBlockId(delta);
        Map<String, Object> metadata = toolCallMetadata(delta, blockId);
        BlockAccumulator block = block(blockId, ContentBlockKind.TOOL_CALL, metadata);
        block.replaceMetadata(metadata);
        return List.of(new MessageDeltaEvent(
            sessionId,
            messageId,
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            blockId,
            ContentBlockKind.TOOL_CALL,
            "",
            delta.complete(),
            metadata,
            timestamp()
        ));
    }

    private List<AgentEvent> done(AssistantDone done) {
        ensureMessageId();
        List<MessageBlockSnapshot> snapshots = blocks.values().stream()
            .map(BlockAccumulator::snapshot)
            .toList();
        return List.of(new MessageEndEvent(
            sessionId,
            messageId,
            MessageRole.ASSISTANT,
            messageKind(snapshots),
            snapshots,
            done.usage(),
            done.stopReason(),
            Map.of(),
            timestamp()
        ));
    }

    private List<AgentEvent> error(AssistantError error) {
        return List.of(new ErrorEvent(sessionId, error.errorId(), error.message(), timestamp()));
    }

    private BlockAccumulator block(String blockId, ContentBlockKind blockKind, Map<String, Object> metadata) {
        return blocks.computeIfAbsent(blockId, id -> new BlockAccumulator(id, blockKind, metadata));
    }

    private String toolCallBlockId(ToolCallDelta delta) {
        if (delta.toolUseId() != null && !delta.toolUseId().isBlank()) {
            return delta.toolUseId();
        }
        String fallbackKey = delta.toolName() == null || delta.toolName().isBlank()
            ? "__unknown_tool_call__"
            : delta.toolName();
        return fallbackToolCallBlockIds.computeIfAbsent(
            fallbackKey,
            ignored -> messageId + ":tool_call:" + fallbackToolCallIndex++
        );
    }

    private Map<String, Object> toolCallMetadata(ToolCallDelta delta, String blockId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolUseId", blockId);
        metadata.put("toolName", delta.toolName() == null ? "" : delta.toolName());
        metadata.put("partialInput", delta.partialInput() == null ? Map.of() : delta.partialInput());
        metadata.put("complete", delta.complete());
        return metadata;
    }

    private MessageKind messageKind(List<MessageBlockSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return MessageKind.TEXT;
        }
        boolean hasText = false;
        boolean hasThinking = false;
        boolean hasToolCall = false;
        for (MessageBlockSnapshot snapshot : snapshots) {
            hasText = hasText || snapshot.blockKind() == ContentBlockKind.TEXT;
            hasThinking = hasThinking || snapshot.blockKind() == ContentBlockKind.THINKING;
            hasToolCall = hasToolCall || snapshot.blockKind() == ContentBlockKind.TOOL_CALL;
        }
        if (hasText) {
            return MessageKind.TEXT;
        }
        if (hasToolCall) {
            return MessageKind.TOOL_CALL;
        }
        if (hasThinking) {
            return MessageKind.THINKING;
        }
        return MessageKind.TEXT;
    }

    private void ensureMessageId() {
        if (messageId == null || messageId.isBlank()) {
            messageId = "msg_unknown";
        }
    }

    private Instant timestamp() {
        return fixedTimestamp == null ? Instant.now(clock) : fixedTimestamp;
    }

    private static final class BlockAccumulator {
        private final String blockId;
        private final ContentBlockKind blockKind;
        private final StringBuilder text = new StringBuilder();
        private Map<String, Object> metadata;

        private BlockAccumulator(String blockId, ContentBlockKind blockKind, Map<String, Object> metadata) {
            this.blockId = blockId;
            this.blockKind = blockKind;
            this.metadata = Map.copyOf(metadata);
        }

        private void append(String delta) {
            text.append(delta == null ? "" : delta);
        }

        private void replaceMetadata(Map<String, Object> metadata) {
            this.metadata = Map.copyOf(metadata);
        }

        private MessageBlockSnapshot snapshot() {
            return new MessageBlockSnapshot(blockId, blockKind, text.toString(), metadata);
        }
    }
}
