package cn.lycode.contracts.session;

import cn.lycode.contracts.model.ThinkingLevel;
import java.time.Instant;

public record ThinkingChangeEntry(
    String id,
    String parentId,
    ThinkingLevel thinkingLevel,
    String reason,
    Instant timestamp
) implements SessionEntry {}

