package cn.lycode.contracts.security;

public record PermissionUpdate(
    PermissionRuleSource targetSource,
    PermissionRule rule
) {}

