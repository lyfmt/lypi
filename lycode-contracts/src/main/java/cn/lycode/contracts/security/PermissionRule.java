package cn.lycode.contracts.security;

public record PermissionRule(
    PermissionRuleSource source,
    PermissionBehavior behavior,
    PermissionRuleValue value,
    String reason
) {}

