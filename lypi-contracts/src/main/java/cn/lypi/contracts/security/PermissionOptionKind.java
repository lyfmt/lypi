package cn.lypi.contracts.security;

public enum PermissionOptionKind {
    ALLOW_ONCE(ReviewDecision.APPROVED, false),
    ALLOW_AND_REMEMBER(ReviewDecision.APPROVED_EXEC_POLICY_AMENDMENT, true),
    DENY(ReviewDecision.DENIED, false),
    CANCEL(ReviewDecision.ABORT, false);

    private final ReviewDecision reviewDecision;
    private final boolean supportsSessionApproval;

    PermissionOptionKind(ReviewDecision reviewDecision, boolean supportsSessionApproval) {
        this.reviewDecision = reviewDecision;
        this.supportsSessionApproval = supportsSessionApproval;
    }

    /**
     * 返回 legacy 选项对应的 canonical review decision。
     */
    public ReviewDecision reviewDecision() {
        return reviewDecision;
    }

    /**
     * 返回该 legacy 选项是否可承载会话级批准语义。
     */
    public boolean supportsSessionApproval() {
        return supportsSessionApproval;
    }
}
