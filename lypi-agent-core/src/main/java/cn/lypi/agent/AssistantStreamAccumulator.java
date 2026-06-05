package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ThinkingContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.model.ToolCallDelta;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class AssistantStreamAccumulator {
    private final AgentMessageFactory messageFactory;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final List<ToolCallContentBlock> toolCalls = new ArrayList<>();
    private String messageId;
    private Optional<TokenUsage> usage = Optional.empty();
    private Optional<String> stopReason = Optional.empty();
    private Optional<AssistantError> error = Optional.empty();
    private boolean completed;

    public AssistantStreamAccumulator(Clock clock) {
        this.messageFactory = new AgentMessageFactory(clock);
    }

    public void accept(AssistantStreamEvent event) {
        switch (event) {
            case AssistantStart start -> messageId = start.messageId();
            case TextDelta delta -> text.append(delta.text());
            case ThinkingDelta delta -> thinking.append(delta.text());
            case ToolCallDelta delta -> toolCalls.add(new ToolCallContentBlock(
                delta.toolUseId(),
                delta.toolName(),
                toJson(delta.partialInput())
            ));
            case AssistantDone done -> {
                usage = done.usage();
                stopReason = done.stopReason();
                completed = true;
            }
            case AssistantError assistantError -> {
                error = Optional.of(assistantError);
                stopReason = Optional.of("error");
                completed = true;
            }
        }
    }

    public AgentMessage toMessage(String fallbackMessageId, boolean aborted) {
        List<ContentBlock> content = new ArrayList<>();
        if (!thinking.isEmpty()) {
            content.add(new ThinkingContentBlock(thinking.toString()));
        }
        if (!text.isEmpty()) {
            content.add(new TextContentBlock(text.toString()));
        }
        content.addAll(toolCalls);
        error.ifPresent(assistantError -> content.add(
            new cn.lypi.contracts.context.ErrorContentBlock(assistantError.errorId(), assistantError.message())
        ));
        if (content.isEmpty()) {
            content.add(new TextContentBlock(""));
        }

        Optional<String> finalStopReason = aborted ? Optional.of("aborted") : stopReason;
        return messageFactory.assistantMessage(
            messageId == null || messageId.isBlank() ? fallbackMessageId : messageId,
            kind(content),
            content,
            usage,
            finalStopReason
        );
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean completed() {
        return completed;
    }

    public Optional<String> stopReason() {
        return stopReason;
    }

    private MessageKind kind(List<ContentBlock> content) {
        if (content.stream().anyMatch(ToolCallContentBlock.class::isInstance)) {
            return MessageKind.TOOL_CALL;
        }
        if (content.stream().anyMatch(c -> c.kind() == cn.lypi.contracts.context.ContentBlockKind.ERROR)) {
            return MessageKind.ERROR;
        }
        if (content.stream().anyMatch(ThinkingContentBlock.class::isInstance) && text.isEmpty()) {
            return MessageKind.THINKING;
        }
        return MessageKind.TEXT;
    }

    private String toJson(Map<String, Object> input) {
        return new TreeMap<>(input).entrySet().stream()
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .map(entry -> "\"" + escape(entry.getKey()) + "\":" + valueToJson(entry.getValue()))
            .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    private String valueToJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "\"" + escape(value.toString()) + "\"";
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
