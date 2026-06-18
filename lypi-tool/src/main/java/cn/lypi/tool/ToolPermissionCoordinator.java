package cn.lypi.tool;

import cn.lypi.contracts.runtime.SandboxPermissions;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 协调安全运行时、工具权限和交互式权限确认。
 */
final class ToolPermissionCoordinator {
    private final SecurityRuntimePort securityRuntime;
    private final PermissionGate permissionGate;
    private final PermissionUpdateStore permissionUpdateStore;
    private final List<PermissionRule> runtimePermissionRules;
    private final SandboxEscalationPolicy sandboxEscalationPolicy;
    private final BashSandboxRiskPolicy bashSandboxRiskPolicy;

    ToolPermissionCoordinator(
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate,
        PermissionUpdateStore permissionUpdateStore,
        List<PermissionRule> runtimePermissionRules,
        SandboxEscalationPolicy sandboxEscalationPolicy,
        BashSandboxRiskPolicy bashSandboxRiskPolicy
    ) {
        this.securityRuntime = securityRuntime;
        this.permissionGate = permissionGate == null ? PermissionGate.denying() : permissionGate;
        this.permissionUpdateStore = permissionUpdateStore == null ? PermissionUpdateStore.noop() : permissionUpdateStore;
        this.runtimePermissionRules = runtimePermissionRules;
        this.sandboxEscalationPolicy = sandboxEscalationPolicy == null ? new SandboxEscalationPolicy() : sandboxEscalationPolicy;
        this.bashSandboxRiskPolicy = bashSandboxRiskPolicy == null ? new BashSandboxRiskPolicy() : bashSandboxRiskPolicy;
    }

    Result authorize(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        Map<String, Object> input,
        ToolUseContext context
    ) {
        PermissionDecision securityDecision = securityRuntime.decide(request, context);
        PermissionDecision effectiveDecision;
        if (isDefaultSandboxBashRequest(request) && canEnterDefaultSandbox(securityDecision)) {
            effectiveDecision = isDeny(securityDecision)
                ? securityDecision
                : isStrictAutoReview(securityDecision)
                    ? securityDecision
                : allowDecision("默认 Bash 请求先进入沙箱执行。");
        } else {
            PermissionDecision toolDecision = tool.checkPermissions(input, context);
            effectiveDecision = effectiveDecision(toolDecision, securityDecision);
        }

        Optional<PermissionDecision> sandboxEscalationDecision = sandboxEscalationPolicy.decide(request, context);
        if (sandboxEscalationDecision.isPresent()) {
            PermissionDecision sandboxDecision = withSuggestedUpdate(
                sandboxEscalationDecision.get(),
                securityDecision.suggestedUpdate()
            );
            effectiveDecision = effectiveDecision(
                isDeny(effectiveDecision) ? effectiveDecision : allowDecision("允许进入沙箱提权审批。"),
                sandboxDecision
            );
        } else if (!isDeny(effectiveDecision)) {
            Optional<PermissionDecision> bashSandboxRiskDecision = bashSandboxRiskPolicy.decide(request, context, securityDecision);
            if (bashSandboxRiskDecision.isPresent()) {
                effectiveDecision = bashSandboxRiskDecision.get();
            }
        }

        if (isDeny(effectiveDecision)) {
            return Result.denied(PermissionGateResult.deny(decisionMessage(effectiveDecision)));
        }

        PermissionGateResult permissionResult = resolvePermission(request, tool, context, effectiveDecision);
        if (permissionResult.status() != PermissionGateResult.Status.ALLOW) {
            return new Result(false, permissionResult);
        }

        permissionResult.permissionUpdate().ifPresent(this::applyPermissionUpdate);
        return new Result(true, permissionResult);
    }

    private void applyPermissionUpdate(PermissionUpdate update) {
        permissionUpdateStore.append(update);
        if (update != null && update.rule() != null) {
            runtimePermissionRules.add(update.rule());
        }
    }

    private PermissionGateResult resolvePermission(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        ToolUseContext context,
        PermissionDecision decision
    ) {
        if (decision == null || decision.behavior() == PermissionBehavior.DENY) {
            return PermissionGateResult.deny(decisionMessage(decision));
        }
        if (decision.behavior() == PermissionBehavior.ALLOW) {
            return PermissionGateResult.allow();
        }
        if (decision.behavior() == PermissionBehavior.ASK
            && isBypassPermissionMode(context)
            && !isStrictAutoReview(decision)) {
            return PermissionGateResult.allow();
        }
        PermissionGateResult result = permissionGate.request(request, tool, context, decision);
        return result == null ? PermissionGateResult.deny("权限请求未获允许。") : result;
    }

    private PermissionDecision effectiveDecision(PermissionDecision toolDecision, PermissionDecision securityDecision) {
        if (isDeny(securityDecision)) {
            return securityDecision;
        }
        if (isDeny(toolDecision)) {
            return toolDecision;
        }
        if (isAsk(securityDecision)) {
            return securityDecision;
        }
        if (isExplicitRuleAllow(securityDecision)) {
            return securityDecision;
        }
        if (isAsk(toolDecision)) {
            return toolDecision;
        }
        return securityDecision == null ? toolDecision : securityDecision;
    }

    private boolean isDefaultSandboxBashRequest(ToolUseRequest request) {
        return request != null
            && "bash".equals(request.toolName())
            && !hasPrefixRule(request.input())
            && SandboxPermissions.fromToolValue(stringInput(request.input(), "sandboxPermissions")) == SandboxPermissions.USE_DEFAULT;
    }

    private boolean canEnterDefaultSandbox(PermissionDecision securityDecision) {
        if (securityDecision == null || securityDecision.behavior() != PermissionBehavior.ASK) {
            return true;
        }
        Object bashRisk = securityDecision.metadata().get("bashRisk");
        return !(bashRisk instanceof BashRiskAnalysis risk) || risk.redirectTargets().isEmpty();
    }

    private boolean hasPrefixRule(Map<String, Object> input) {
        return input != null && input.containsKey("prefix_rule");
    }

    private String stringInput(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? "" : value.toString();
    }

    private boolean isBypassPermissionMode(ToolUseContext context) {
        Object value = context.metadata().get(ToolRuntimeContextFactory.METADATA_PERMISSION_MODE);
        if (value instanceof PermissionMode permissionMode) {
            return permissionMode == PermissionMode.BYPASS;
        }
        if (value instanceof String permissionMode) {
            return PermissionMode.valueOf(permissionMode) == PermissionMode.BYPASS;
        }
        return false;
    }

    private boolean isStrictAutoReview(PermissionDecision decision) {
        Object value = decision.metadata().get("strictAutoReview");
        if (value instanceof Boolean strictAutoReview) {
            return strictAutoReview;
        }
        return value instanceof String strictAutoReview && Boolean.parseBoolean(strictAutoReview);
    }

    private boolean isDeny(PermissionDecision decision) {
        return decision == null || decision.behavior() == PermissionBehavior.DENY;
    }

    private boolean isAsk(PermissionDecision decision) {
        return decision != null && decision.behavior() == PermissionBehavior.ASK;
    }

    private boolean isExplicitRuleAllow(PermissionDecision decision) {
        return decision != null
            && decision.behavior() == PermissionBehavior.ALLOW
            && decision.reason() == PermissionDecisionReason.EXPLICIT_RULE;
    }

    private PermissionDecision allowDecision(String message) {
        return new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.MODE_DEFAULT,
            message,
            Optional.empty(),
            Map.of()
        );
    }

    private PermissionDecision withSuggestedUpdate(
        PermissionDecision decision,
        Optional<PermissionUpdate> suggestedUpdate
    ) {
        if (decision == null || suggestedUpdate == null || suggestedUpdate.isEmpty() || decision.suggestedUpdate().isPresent()) {
            return decision;
        }
        return new PermissionDecision(
            decision.behavior(),
            decision.reason(),
            decision.message(),
            suggestedUpdate,
            decision.metadata()
        );
    }

    private String decisionMessage(PermissionDecision decision) {
        if (decision == null || decision.message() == null || decision.message().isBlank()) {
            return "未提供原因。";
        }
        return decision.message();
    }

    record Result(boolean allowed, PermissionGateResult gateResult) {
        private static Result denied(PermissionGateResult result) {
            return new Result(false, result);
        }
    }
}
