package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ErrorContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.model.TokenUsage;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

public final class AgentMessageFactory {
    private final Clock clock;

    public AgentMessageFactory(Clock clock) {
        this.clock = clock;
    }

    public AgentMessage userMessage(String messageId, String text) {
        return new AgentMessage(
            messageId,
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text)),
            clock.instant(),
            Optional.empty(),
            Optional.empty()
        );
    }

    public AgentMessage assistantMessage(
        String messageId,
        MessageKind kind,
        List<ContentBlock> content,
        Optional<TokenUsage> usage,
        Optional<String> stopReason
    ) {
        return new AgentMessage(
            messageId,
            MessageRole.ASSISTANT,
            kind,
            List.copyOf(content),
            clock.instant(),
            usage,
            stopReason
        );
    }

    public AgentMessage toolResultMessage(String messageId, String toolUseId, String text, boolean error) {
        return new AgentMessage(
            messageId,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, text, error)),
            clock.instant(),
            Optional.empty(),
            Optional.empty()
        );
    }

    public AgentMessage toolResultMessage(
        String messageId,
        List<ToolResultContentBlock> blocks,
        Optional<String> stopReason
    ) {
        return new AgentMessage(
            messageId,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.copyOf(blocks),
            clock.instant(),
            Optional.empty(),
            stopReason
        );
    }

    public AgentMessage errorMessage(String messageId, String errorId, String text) {
        return new AgentMessage(
            messageId,
            MessageRole.ASSISTANT,
            MessageKind.ERROR,
            List.of(new ErrorContentBlock(errorId, text)),
            clock.instant(),
            Optional.empty(),
            Optional.of("error")
        );
    }
}
