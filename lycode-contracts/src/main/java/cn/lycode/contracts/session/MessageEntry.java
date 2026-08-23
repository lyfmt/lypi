package cn.lycode.contracts.session;

import cn.lycode.contracts.context.AgentMessage;
import java.time.Instant;

public record MessageEntry(
    String id,
    String parentId,
    AgentMessage message,
    Instant timestamp
) implements SessionEntry {}

