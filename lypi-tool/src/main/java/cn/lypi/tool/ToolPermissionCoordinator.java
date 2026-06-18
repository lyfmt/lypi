package cn.lypi.tool;

import cn.lypi.contracts.runtime.SandboxPermissions;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.NetworkPolicyMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 协调安全运行时、工具权限和交互式权限确认。
 */
final class ToolPermissionCoordinator {
    private static final String INPUT_ADDITIONAL_PERMISSIONS = "additionalPermissions";
    private static final String INPUT_SANDBOX_PERMISSIONS = "sandboxPermissions";
    private static final String METADATA_ADDITIONAL_PERMISSIONS = "additionalPermissions";
    private static final String METADATA_APPROVED_ADDITIONAL_PERMISSIONS = "approvedAdditionalPermissions";

    private final SecurityRuntimePort securityRuntime;
    private final ApprovalCoordinator approvalCoordinator;
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
        this.approvalCoordinator = new ApprovalCoordinator(
            permissionGate,
            permissionUpdateStore,
            runtimePermissionRules,
            new ApprovalRequestFactory()
        );
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
        this.securityRuntime = securityRuntime;
        this.approvalCoordinator = new ApprovalCoordinator(
            permissionGate,
            permissionUpdateStore,
            runtimePermissionRules,
            new ApprovalRequestFactory()
        );
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
        Optional<Result> additionalPermissionsResult = additionalPermissionsResult(
            request,
            tool,
            context,
            securityDecision
        );
        if (additionalPermissionsResult.isPresent()) {
            return additionalPermissionsResult.get();
        }

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

        PermissionGateResult permissionResult = approvalCoordinator.resolve(request, tool, context, effectiveDecision);
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

    private boolean isDefaultSandboxBashRequest(ToolUseRequest request) {
        return request != null
            && "bash".equals(request.toolName())
            && !hasPrefixRule(request.input())
            && SandboxPermissions.fromToolValue(stringInput(request.input(), INPUT_SANDBOX_PERMISSIONS)) == SandboxPermissions.USE_DEFAULT;
    }

    private Optional<Result> additionalPermissionsResult(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        ToolUseContext context,
        PermissionDecision securityDecision
    ) {
        if (request == null || !"bash".equals(request.toolName())) {
            return Optional.empty();
        }
        if (SandboxPermissions.fromToolValue(stringInput(request.input(), INPUT_SANDBOX_PERMISSIONS))
            != SandboxPermissions.WITH_ADDITIONAL_PERMISSIONS) {
            return Optional.empty();
        }
        Optional<AdditionalPermissionProfile> preapproved = approvedAdditionalPermissions(context);
        if (isDeny(securityDecision)) {
            return Optional.of(Result.denied(PermissionGateResult.deny(decisionMessage(securityDecision))));
        }
        Object rawPermissions = request.input() == null ? null : request.input().get(INPUT_ADDITIONAL_PERMISSIONS);
        if (rawPermissions == null) {
            return Optional.of(Result.denied(PermissionGateResult.deny(
                "sandboxPermissions=withAdditionalPermissions 时 additionalPermissions 不能为空。"
            )));
        }
        AdditionalPermissionProfile additionalPermissions;
        try {
            additionalPermissions = AdditionalPermissionsInputParser.parse(rawPermissions, INPUT_ADDITIONAL_PERMISSIONS);
        } catch (IllegalArgumentException exception) {
            return Optional.of(Result.denied(PermissionGateResult.deny(exception.getMessage())));
        }
        if (AdditionalPermissionsInputParser.isEmpty(additionalPermissions)) {
            return Optional.of(Result.denied(PermissionGateResult.deny(
                "sandboxPermissions=withAdditionalPermissions 时 additionalPermissions 不能为空。"
            )));
        }
        String reason = securityDecision == null || securityDecision.message() == null || securityDecision.message().isBlank()
            ? "请求额外权限。"
            : securityDecision.message();
        PermissionGateResult permissionResult = approvalCoordinator.resolveAdditionalPermissions(
            request,
            tool,
            context,
            reason,
            additionalPermissions
        );
        if (permissionResult.status() != PermissionGateResult.Status.ALLOW) {
            return Optional.of(Result.disallowed(permissionResult));
        }
        return Optional.of(Result.allowed(
            permissionResult,
            mergeAdditionalPermissions(preapproved.orElse(AdditionalPermissionProfile.empty()), additionalPermissions)
        ));
    }

    private Optional<AdditionalPermissionProfile> approvedAdditionalPermissions(ToolUseContext context) {
        Object marker = context.metadata().get(METADATA_APPROVED_ADDITIONAL_PERMISSIONS);
        boolean approved = false;
        if (marker instanceof Boolean booleanMarker) {
            approved = booleanMarker;
        } else if (marker instanceof String stringMarker) {
            approved = Boolean.parseBoolean(stringMarker);
        }
        if (!approved) {
            return Optional.empty();
        }
        Object value = context.metadata().get(METADATA_ADDITIONAL_PERMISSIONS);
        return value instanceof AdditionalPermissionProfile permissions ? Optional.of(permissions) : Optional.empty();
    }

    private AdditionalPermissionProfile mergeAdditionalPermissions(
        AdditionalPermissionProfile first,
        AdditionalPermissionProfile second
    ) {
        AdditionalPermissionProfile left = first == null ? AdditionalPermissionProfile.empty() : first;
        AdditionalPermissionProfile right = second == null ? AdditionalPermissionProfile.empty() : second;
        return new AdditionalPermissionProfile(
            mergeFileSystem(left, right),
            mergeNetwork(left, right)
        );
    }

    private Optional<FileSystemPermissionPolicy> mergeFileSystem(
        AdditionalPermissionProfile left,
        AdditionalPermissionProfile right
    ) {
        if (left.fileSystem().isEmpty()) {
            return right.fileSystem();
        }
        if (right.fileSystem().isEmpty()) {
            return left.fileSystem();
        }
        FileSystemPermissionPolicy leftPolicy = left.fileSystem().orElseThrow();
        FileSystemPermissionPolicy rightPolicy = right.fileSystem().orElseThrow();
        if (leftPolicy.kind() != rightPolicy.kind()
            || leftPolicy.kind() != FileSystemPolicyKind.RESTRICTED) {
            return right.fileSystem();
        }
        ArrayList<FileSystemPermissionEntry> entries = new ArrayList<>(leftPolicy.entries());
        entries.addAll(rightPolicy.entries());
        return Optional.of(FileSystemPermissionPolicy.restricted(entries));
    }

    private Optional<NetworkPermissionPolicy> mergeNetwork(
        AdditionalPermissionProfile left,
        AdditionalPermissionProfile right
    ) {
        if (left.network().isEmpty()) {
            return right.network();
        }
        if (right.network().isEmpty()) {
            return left.network();
        }
        if (left.network().orElseThrow().mode() == NetworkPolicyMode.ENABLED
            || right.network().orElseThrow().mode() == NetworkPolicyMode.ENABLED) {
            return Optional.of(NetworkPermissionPolicy.enabled());
        }
        return left.network();
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

        private static Result allowed(PermissionGateResult result) {
            return new Result(true, result, Optional.empty());
        }

        private static Result disallowed(PermissionGateResult result) {
            return new Result(false, result, Optional.empty());
        }

        private static Result allowed(
            PermissionGateResult result,
            AdditionalPermissionProfile additionalPermissions
        ) {
            return new Result(true, result, Optional.of(additionalPermissions));
        }

        private static Result denied(PermissionGateResult result) {
            return new Result(false, result, Optional.empty());
        }
    }
}
