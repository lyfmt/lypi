package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ThinkingContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.model.ProviderConversationState;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.ProviderRetryNotice;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.model.ToolCallDelta;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AssistantStreamAccumulator {
    private final AgentMessageFactory messageFactory;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final Map<String, ToolCallState> toolCalls = new LinkedHashMap<>();
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
            case ToolCallDelta delta -> toolCalls
                .computeIfAbsent(delta.toolUseId(), ignored -> new ToolCallState(delta.toolUseId(), delta.toolName()))
                .merge(delta);
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
            case ProviderRetryNotice ignored -> {
            }
        }
    }

    public AgentMessage toMessage(String fallbackMessageId, boolean aborted) {
        return toMessage(fallbackMessageId, aborted, Optional.empty());
    }

    public AgentMessage toMessage(
        String fallbackMessageId,
        boolean aborted,
        Optional<ProviderConversationState> providerConversationState
    ) {
        List<ContentBlock> content = new ArrayList<>();
        if (!thinking.isEmpty()) {
            content.add(new ThinkingContentBlock(thinking.toString()));
        }
        if (!text.isEmpty()) {
            content.add(new TextContentBlock(text.toString()));
        }
        content.addAll(toolCalls.values().stream().map(ToolCallState::toBlock).toList());
        error.ifPresent(assistantError -> content.add(
            new cn.lypi.contracts.context.ErrorContentBlock(assistantError.errorId(), assistantError.message())
        ));
        if (content.isEmpty()) {
            content.add(new TextContentBlock(""));
        }
        providerConversationState.ifPresent(state -> attachProviderConversationState(content, state));

        Optional<String> finalStopReason = aborted ? Optional.of("aborted") : stopReason;
        return messageFactory.assistantMessage(
            messageId == null || messageId.isBlank() ? fallbackMessageId : messageId,
            kind(content),
            content,
            usage,
            finalStopReason
        );
    }

    private void attachProviderConversationState(List<ContentBlock> content, ProviderConversationState state) {
        if (content.isEmpty()) {
            return;
        }
        ContentBlock first = content.getFirst();
        Map<String, Object> metadata = new LinkedHashMap<>(first.metadata());
        Map<String, Object> stateMetadata = new LinkedHashMap<>();
        stateMetadata.put("provider", state.provider());
        stateMetadata.put("style", state.style());
        state.previousResponseId().ifPresent(id -> stateMetadata.put("previousResponseId", id));
        metadata.put("providerConversationState", Collections.unmodifiableMap(stateMetadata));
        content.set(0, withMetadata(first, Collections.unmodifiableMap(metadata)));
    }

    private ContentBlock withMetadata(ContentBlock block, Map<String, Object> metadata) {
        return switch (block) {
            case TextContentBlock text -> new TextContentBlock(text.text(), metadata);
            case ThinkingContentBlock thinking -> new ThinkingContentBlock(thinking.text(), metadata);
            case ToolCallContentBlock toolCall -> new ToolCallContentBlock(
                toolCall.toolUseId(),
                toolCall.toolName(),
                toolCall.text(),
                metadata
            );
            case cn.lypi.contracts.context.ErrorContentBlock error -> new cn.lypi.contracts.context.ErrorContentBlock(
                error.errorId(),
                error.text(),
                metadata
            );
            default -> block;
        };
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

    public Optional<String> messageId() {
        return Optional.ofNullable(messageId).filter(id -> !id.isBlank());
    }

    private MessageKind kind(List<ContentBlock> content) {
        if (content.stream().anyMatch(c -> c.kind() == cn.lypi.contracts.context.ContentBlockKind.ERROR)) {
            return MessageKind.ERROR;
        }
        if (content.stream().anyMatch(ToolCallContentBlock.class::isInstance)) {
            return MessageKind.TOOL_CALL;
        }
        if (content.stream().anyMatch(ThinkingContentBlock.class::isInstance) && text.isEmpty()) {
            return MessageKind.THINKING;
        }
        return MessageKind.TEXT;
    }

    private static final class ToolCallState {
        private final String toolUseId;
        private final String toolName;
        private final Map<String, Object> input = new LinkedHashMap<>();
        private boolean complete;

        private ToolCallState(String toolUseId, String toolName) {
            this.toolUseId = toolUseId;
            this.toolName = toolName;
        }

        private void merge(ToolCallDelta delta) {
            input.putAll(delta.partialInput());
            complete = complete || delta.complete();
        }

        private ToolCallContentBlock toBlock() {
            return new ToolCallContentBlock(
                toolUseId,
                toolName,
                "",
                Map.of(
                    "input", Collections.unmodifiableMap(new LinkedHashMap<>(input)),
                    "complete", complete
                )
            );
        }
    }
}
