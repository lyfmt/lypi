package cn.lycode.contracts.tool;

import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.ContentReplacementRecord;
import java.util.List;
import java.util.Optional;

public record ToolResult<O>(
    O output,
    boolean isError,
    List<AgentMessage> newMessages,
    Optional<ContentReplacementRecord> replacement,
    Optional<ToolStateDelta> stateDelta
) {
    public ToolResult(
        O output,
        boolean isError,
        List<AgentMessage> newMessages,
        Optional<ContentReplacementRecord> replacement
    ) {
        this(output, isError, newMessages, replacement, Optional.empty());
    }

    public ToolResult {
        stateDelta = stateDelta == null ? Optional.empty() : stateDelta;
    }

    public ToolResult<O> withStateDelta(Optional<ToolStateDelta> nextStateDelta) {
        return new ToolResult<>(output, isError, newMessages, replacement, nextStateDelta);
    }
}
