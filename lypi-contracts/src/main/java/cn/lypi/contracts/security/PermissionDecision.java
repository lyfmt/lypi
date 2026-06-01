package cn.lypi.contracts.security;

import java.util.Map;
import java.util.Optional;

public record PermissionDecision(
    PermissionBehavior behavior,
    PermissionDecisionReason reason,
    String message,
    Optional<PermissionUpdate> suggestedUpdate,
    Map<String, Object> metadata
) {}

