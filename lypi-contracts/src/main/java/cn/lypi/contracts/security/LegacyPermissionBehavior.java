package cn.lypi.contracts.security;

public record LegacyPermissionBehavior(
    boolean defaultBashRequiresEscalation,
    boolean allowExplicitEscalationWithoutPrompt,
    boolean hardSafetyEnabled
) {}
