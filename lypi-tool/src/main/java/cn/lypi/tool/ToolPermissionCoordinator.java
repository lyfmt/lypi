package cn.lypi.tool;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuntimeState;
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
    private final ApprovalCoordinator approvalCoordinator;
    private final InlineAdditionalPermissionsAuthorizer additionalPermissionsAuthorizer;
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
        this(
            securityRuntime,
            permissionGate,
            permissionUpdateStore,
            runtimePermissionRules,
            sandboxEscalationPolicy,
            bashSandboxRiskPolicy,
            PermissionReviewer.denying()
        );
    }

    ToolPermissionCoordinator(
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate,
        PermissionUpdateStore permissionUpdateStore,
        List<PermissionRule> runtimePermissionRules,
        SandboxEscalationPolicy sandboxEscalationPolicy,
        BashSandboxRiskPolicy bashSandboxRiskPolicy,
        PermissionReviewer permissionReviewer
    ) {
        this.securityRuntime = securityRuntime;
        this.approvalCoordinator = new ApprovalCoordinator(
            permissionGate,
            permissionUpdateStore,
            new RuntimePermissionRuleStore(runtimePermissionRules),
            new ApprovalRequestFactory(),
            permissionReviewer
        );
        this.additionalPermissionsAuthorizer = new InlineAdditionalPermissionsAuthorizer(this.approvalCoordinator);
        this.sandboxEscalationPolicy = sandboxEscalationPolicy == null ? new SandboxEscalationPolicy() : sandboxEscalationPolicy;
        this.bashSandboxRiskPolicy = bashSandboxRiskPolicy == null ? new BashSandboxRiskPolicy() : bashSandboxRiskPolicy;
    }

    ToolPermissionCoordinator(
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate,
        PermissionUpdateStore permissionUpdateStore,
        RuntimePermissionRuleStore runtimePermissionRules,
        SandboxEscalationPolicy sandboxEscalationPolicy,
        BashSandboxRiskPolicy bashSandboxRiskPolicy
    ) {
        this(
            securityRuntime,
            permissionGate,
            permissionUpdateStore,
            runtimePermissionRules,
            sandboxEscalationPolicy,
            bashSandboxRiskPolicy,
            PermissionReviewer.denying()
        );
    }

    ToolPermissionCoordinator(
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate,
        PermissionUpdateStore permissionUpdateStore,
        RuntimePermissionRuleStore runtimePermissionRules,
        SandboxEscalationPolicy sandboxEscalationPolicy,
        BashSandboxRiskPolicy bashSandboxRiskPolicy,
        PermissionReviewer permissionReviewer
    ) {
        this.securityRuntime = securityRuntime;
        this.approvalCoordinator = new ApprovalCoordinator(
            permissionGate,
            permissionUpdateStore,
            runtimePermissionRules,
            new ApprovalRequestFactory(),
            permissionReviewer
        );
        this.additionalPermissionsAuthorizer = new InlineAdditionalPermissionsAuthorizer(this.approvalCoordinator);
        this.sandboxEscalationPolicy = sandboxEscalationPolicy == null ? new SandboxEscalationPolicy() : sandboxEscalationPolicy;
        this.bashSandboxRiskPolicy = bashSandboxRiskPolicy == null ? new BashSandboxRiskPolicy() : bashSandboxRiskPolicy;
    }

    Result authorize(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        Map<String, Object> input,
        ToolUseContext context
    ) {
        return authorize(request, tool, input, context, null);
    }

    Result authorize(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        Map<String, Object> input,
        ToolUseContext context,
        ContextSnapshot contextSnapshot
    ) {
        PermissionMode mode = runtimeState(context).mode();
        if (mode == PermissionMode.BYPASS) {
            return additionalPermissionsAuthorizer.authorizeBypass(request, context)
                .orElseGet(() -> Result.allowed(PermissionGateResult.allow()));
        }
        if (tool.isReadOnly(input)) {
            return Result.allowed(PermissionGateResult.allow());
        }

        PermissionDecision securityDecision = securityRuntime.decide(request, context);
        PermissionDecision toolDecision = tool.checkPermissions(input, context);
        PermissionDecision effectiveDecision = effectiveDecision(toolDecision, securityDecision);

        Optional<PermissionDecision> sandboxEscalationDecision = sandboxEscalationPolicy.decide(request, context);
        if (sandboxEscalationDecision.isPresent()) {
            PermissionDecision sandboxDecision = withSuggestedUpdate(
                sandboxEscalationDecision.get(),
                securityDecision == null ? Optional.empty() : securityDecision.suggestedUpdate()
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

        Optional<Result> additionalPermissionsResult = additionalPermissionsAuthorizer.authorize(
            request,
            tool,
            context,
            contextSnapshot,
            effectiveDecision
        );
        if (additionalPermissionsResult.isPresent()) {
            return additionalPermissionsResult.get();
        }

        PermissionGateResult permissionResult = approvalCoordinator.resolve(
            request,
            tool,
            context,
            contextSnapshot,
            reviewDecision(effectiveDecision)
        );
        if (permissionResult.status() != PermissionGateResult.Status.ALLOW) {
            return Result.disallowed(permissionResult);
        }

        return Result.allowed(permissionResult);
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

    private PermissionDecision reviewDecision(PermissionDecision decision) {
        if (decision == null) {
            return new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.MODE_DEFAULT,
                "权限判定未提供原因。",
                Optional.empty(),
                Map.of()
            );
        }
        return new PermissionDecision(
            PermissionBehavior.ASK,
            decision.reason(),
            decisionMessage(decision),
            decision.suggestedUpdate(),
            decision.metadata()
        );
    }

    private PermissionRuntimeState runtimeState(ToolUseContext context) {
        Object state = context.metadata().get(ToolRuntimeContextFactory.METADATA_PERMISSION_RUNTIME_STATE);
        if (state instanceof PermissionRuntimeState runtimeState) {
            return runtimeState;
        }
        Object mode = context.metadata().get(ToolRuntimeContextFactory.METADATA_PERMISSION_MODE);
        if (mode instanceof PermissionMode permissionMode) {
            return PermissionRuntimeState.forMode(permissionMode);
        }
        if (mode instanceof String permissionMode && !permissionMode.isBlank()) {
            return PermissionRuntimeState.forMode(PermissionMode.fromJson(permissionMode));
        }
        return PermissionRuntimeState.forMode(PermissionMode.ASK);
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

    record Result(
        boolean allowed,
        PermissionGateResult gateResult,
        Optional<AdditionalPermissionProfile> approvedAdditionalPermissions
    ) {
        Result(boolean allowed, PermissionGateResult gateResult) {
            this(allowed, gateResult, Optional.empty());
        }

        Result {
            approvedAdditionalPermissions = approvedAdditionalPermissions == null
                ? Optional.empty()
                : approvedAdditionalPermissions;
        }

        static Result allowed(PermissionGateResult result) {
            return new Result(true, result, Optional.empty());
        }

        static Result disallowed(PermissionGateResult result) {
            return new Result(false, result, Optional.empty());
        }

        static Result allowed(
            PermissionGateResult result,
            AdditionalPermissionProfile additionalPermissions
        ) {
            return new Result(true, result, Optional.of(additionalPermissions));
        }

        static Result denied(PermissionGateResult result) {
            return new Result(false, result, Optional.empty());
        }
    }
}
