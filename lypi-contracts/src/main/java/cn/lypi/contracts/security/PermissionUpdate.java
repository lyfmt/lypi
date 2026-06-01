package cn.lypi.contracts.security;

public record PermissionUpdate(
    PermissionRuleSource targetSource,
    PermissionRule rule
) {}

