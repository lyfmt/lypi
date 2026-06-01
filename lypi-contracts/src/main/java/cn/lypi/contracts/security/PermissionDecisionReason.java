package cn.lypi.contracts.security;

public enum PermissionDecisionReason {
    HARD_SAFETY,
    EXPLICIT_RULE,
    TOOL_SPECIFIC,
    PATH_SAFETY,
    BASH_RISK,
    MODE_DEFAULT,
    SANDBOX_POLICY
}

