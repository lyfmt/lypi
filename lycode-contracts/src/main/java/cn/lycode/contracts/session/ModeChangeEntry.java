package cn.lycode.contracts.session;

import cn.lycode.contracts.security.AgentMode;
import java.time.Instant;

public record ModeChangeEntry(
    String id,
    String parentId,
    AgentMode agentMode,
    String reason,
    Instant timestamp
) implements SessionEntry {}

