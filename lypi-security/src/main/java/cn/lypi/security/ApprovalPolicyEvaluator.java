package cn.lypi.security;

import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.GranularApprovalPolicy;
import java.util.Objects;

/**
 * 根据审批策略判断是否允许进入人工确认。
 */
public final class ApprovalPolicyEvaluator {
    /**
     * 判断审批策略是否允许当前请求弹出确认。
     */
    public Decision evaluate(ApprovalPolicy policy, ApprovalCategory category, boolean interactive) {
        ApprovalPolicy safePolicy = Objects.requireNonNull(policy, "policy");
        ApprovalCategory safeCategory = category == null ? ApprovalCategory.COMMAND : category;
        if (!interactive) {
            return Decision.deny("non-interactive runtime cannot prompt for approval");
        }
        ApprovalMode mode = modeFor(safePolicy, safeCategory);
        return switch (mode) {
            case ON_REQUEST, UNLESS_TRUSTED, ON_FAILURE -> Decision.allow();
            case NEVER -> Decision.deny(safeCategory.reasonName() + " approval is disabled by never policy");
            case GRANULAR -> throw new IllegalStateException("nested granular approval mode is not supported");
        };
    }

    private ApprovalMode modeFor(ApprovalPolicy policy, ApprovalCategory category) {
        if (policy.mode() != ApprovalMode.GRANULAR) {
            return policy.mode();
        }
        GranularApprovalPolicy granularPolicy = policy.granularApprovalPolicy().orElseThrow();
        return switch (category) {
            case COMMAND -> granularPolicy.rules();
            case SANDBOX -> granularPolicy.sandboxApproval();
            case REQUEST_PERMISSIONS -> granularPolicy.requestPermissions();
            case MCP_ELICITATION -> granularPolicy.mcpElicitations();
            case SKILL -> granularPolicy.skillApproval();
        };
    }

    public enum ApprovalCategory {
        COMMAND("command"),
        SANDBOX("sandbox"),
        REQUEST_PERMISSIONS("request_permissions"),
        MCP_ELICITATION("mcp elicitation"),
        SKILL("skill");

        private final String reasonName;

        ApprovalCategory(String reasonName) {
            this.reasonName = reasonName;
        }

        private String reasonName() {
            return reasonName;
        }
    }

    public enum Outcome {
        ALLOW_TO_PROMPT,
        DENY_WITH_REASON
    }

    public record Decision(
        Outcome outcome,
        String reason
    ) {
        private static Decision allow() {
            return new Decision(Outcome.ALLOW_TO_PROMPT, "");
        }

        private static Decision deny(String reason) {
            return new Decision(Outcome.DENY_WITH_REASON, reason);
        }
    }
}
