package cn.lypi.contracts.tui;

public record PermissionPromptView(
    String toolUseId,
    String reason,
    String rule,
    String defaultOptionId,
    String cancelOptionId
) {
    public PermissionPromptView {
        reason = reason == null ? "" : reason;
        rule = rule == null ? "" : rule;
        defaultOptionId = defaultOptionId == null || defaultOptionId.isBlank() ? rule : defaultOptionId;
        cancelOptionId = cancelOptionId == null ? "" : cancelOptionId;
    }

    public PermissionPromptView(String toolUseId, String reason, String rule) {
        this(toolUseId, reason, rule, rule, "");
    }
}
