package cn.lycode.tool;

import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.runtime.SecurityRuntimePort;
import cn.lycode.contracts.security.AdditionalPermissionProfile;
import cn.lycode.contracts.security.PermissionBehavior;
import cn.lycode.contracts.security.PermissionDecision;
import cn.lycode.contracts.security.PermissionDecisionReason;
import cn.lycode.contracts.security.PermissionMode;
import cn.lycode.contracts.security.PermissionRule;
import cn.lycode.contracts.security.PermissionRuntimeState;
import cn.lycode.contracts.security.PermissionUpdate;
import cn.lycode.contracts.tool.Tool;
import cn.lycode.contracts.tool.ToolUseContext;
import cn.lycode.contracts.tool.ToolUseRequest;
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

    ToolPermissionCoordinator(
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate,
        PermissionUpdateStore permissionUpdateStore,
        List<PermissionRule> runtimePermissionRules,
        SandboxEscalationPolicy sandboxEscalationPolicy
    ) {
        this(
            securityRuntime,
            permissionGate,
            permissionUpdateStore,
            runtimePermissionRules,
            sandboxEscalationPolicy,
            PermissionReviewer.denying()
        );
    }

    ToolPermissionCoordinator(
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate,
        PermissionUpdateStore permissionUpdateStore,
        List<PermissionRule> runtimePermissionRules,
        SandboxEscalationPolicy sandboxEscalationPolicy,
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
    }

    ToolPermissionCoordinator(
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate,
        PermissionUpdateStore permissionUpdateStore,
        RuntimePermissionRuleStore runtimePermissionRules,
        SandboxEscalationPolicy sandboxEscalationPolicy
    ) {
        this(
            securityRuntime,
            permissionGate,
            permissionUpdateStore,
            runtimePermissionRules,
            sandboxEscalationPolicy,
            PermissionReviewer.denying()
        );
    }

    ToolPermissionCoordinator(
        SecurityRuntimePort securityRuntime,
        PermissionGate permissionGate,
        PermissionUpdateStore permissionUpdateStore,
        RuntimePermissionRuleStore runtimePermissionRules,
        SandboxEscalationPolicy sandboxEscalationPolicy,
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
                .orElseGet(() -> Result.directlyAllowed(PermissionGateResult.allow()));
        }
        if (tool.isReadOnly(input)) {
            return Result.directlyAllowed(PermissionGateResult.allow());
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

        if (effectiveDecision != null && effectiveDecision.behavior() == PermissionBehavior.ALLOW) {
            return Result.directlyAllowed(PermissionGateResult.allow());
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

        return Result.approved(permissionResult);
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
        boolean approvedDuringCurrentCall,
        Optional<AdditionalPermissionProfile> approvedAdditionalPermissions
    ) {
        Result {
            approvedAdditionalPermissions = approvedAdditionalPermissions == null
                ? Optional.empty()
                : approvedAdditionalPermissions;
        }

        static Result directlyAllowed(PermissionGateResult result) {
            return new Result(true, result, false, Optional.empty());
        }

        static Result directlyAllowed(
            PermissionGateResult result,
            AdditionalPermissionProfile additionalPermissions
        ) {
            return new Result(true, result, false, Optional.of(additionalPermissions));
        }

        static Result approved(PermissionGateResult result) {
            return new Result(true, result, true, Optional.empty());
        }

        static Result disallowed(PermissionGateResult result) {
            return new Result(false, result, false, Optional.empty());
        }

        static Result approved(
            PermissionGateResult result,
            AdditionalPermissionProfile additionalPermissions
        ) {
            return new Result(true, result, true, Optional.of(additionalPermissions));
        }
    }
}
