package cn.lypi.contracts.session;

import cn.lypi.contracts.context.AgentMessage;
import java.time.Instant;

public record MessageEntry(
    String id,
    String parentId,
    AgentMessage message,
    Instant timestamp
) implements SessionEntry {}

