package cn.lycode.contracts.security;

public record LegacyPermissionBehavior(
    boolean defaultBashRequiresEscalation,
    boolean allowExplicitEscalationWithoutPrompt,
    boolean hardSafetyEnabled
) {}
