package cn.lypi.contracts.security;

import java.util.Objects;

public record GranularApprovalPolicy(
    ApprovalMode sandboxApproval,
    ApprovalMode rules,
    ApprovalMode skillApproval,
    ApprovalMode requestPermissions,
    ApprovalMode mcpElicitations
) {
    public GranularApprovalPolicy {
        sandboxApproval = Objects.requireNonNull(sandboxApproval, "sandboxApproval");
        rules = Objects.requireNonNull(rules, "rules");
        skillApproval = Objects.requireNonNull(skillApproval, "skillApproval");
        requestPermissions = Objects.requireNonNull(requestPermissions, "requestPermissions");
        mcpElicitations = Objects.requireNonNull(mcpElicitations, "mcpElicitations");
    }
}
