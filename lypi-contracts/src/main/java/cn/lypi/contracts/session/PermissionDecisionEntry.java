package cn.lypi.contracts.session;

import cn.lypi.contracts.security.PermissionDecision;
import java.time.Instant;

public record PermissionDecisionEntry(
    String id,
    String parentId,
    String toolUseId,
    String toolName,
    String renderedToolUse,
    PermissionDecision decision,
    Instant timestamp
) implements SessionEntry {}
