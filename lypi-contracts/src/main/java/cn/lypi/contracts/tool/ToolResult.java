package cn.lypi.contracts.tool;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentReplacementRecord;
import java.util.List;
import java.util.Optional;

public record ToolResult<O>(
    O output,
    boolean isError,
    List<AgentMessage> newMessages,
    Optional<ContentReplacementRecord> replacement
) {}

