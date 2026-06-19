package cn.lypi.contracts.tui;

public record StatusBarState(
    String sessionId,
    String model,
    String mode,
    String permissionMode,
    String approvalMode,
    String activePermissionProfileId,
    String cwd,
    String branchLeafId,
    String budget,
    boolean hasInterruptibleTool
) {
    public StatusBarState {
        sessionId = nullToEmpty(sessionId);
        model = nullToEmpty(model);
        mode = nullToEmpty(mode);
        permissionMode = nullToEmpty(permissionMode);
        approvalMode = nullToEmpty(approvalMode);
        activePermissionProfileId = nullToEmpty(activePermissionProfileId);
        cwd = nullToEmpty(cwd);
        branchLeafId = nullToEmpty(branchLeafId);
        budget = nullToEmpty(budget);
    }

    public StatusBarState(
        String sessionId,
        String model,
        String mode,
        String permissionMode,
        String cwd,
        String branchLeafId,
        String budget,
        boolean hasInterruptibleTool
    ) {
        this(sessionId, model, mode, permissionMode, "", "", cwd, branchLeafId, budget, hasInterruptibleTool);
    }

    public StatusBarState(String sessionId, String model, String mode, String permissionMode) {
        this(sessionId, model, mode, permissionMode, "", "", "", false);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
