package cn.lypi.contracts.tui;

public record PermissionPromptView(
    String requestId,
    String toolUseId,
    String reason,
    String rule,
    String defaultOptionId,
    String cancelOptionId
) {
    public PermissionPromptView {
        requestId = requestId == null || requestId.isBlank() ? toolUseId : requestId;
        reason = reason == null ? "" : reason;
        rule = rule == null ? "" : rule;
        defaultOptionId = defaultOptionId == null || defaultOptionId.isBlank() ? rule : defaultOptionId;
        cancelOptionId = cancelOptionId == null ? "" : cancelOptionId;
    }

    public PermissionPromptView(String toolUseId, String reason, String rule, String defaultOptionId, String cancelOptionId) {
        this(toolUseId, toolUseId, reason, rule, defaultOptionId, cancelOptionId);
    }

    public PermissionPromptView(String toolUseId, String reason, String rule) {
        this(toolUseId, toolUseId, reason, rule, rule, "");
    }
}
