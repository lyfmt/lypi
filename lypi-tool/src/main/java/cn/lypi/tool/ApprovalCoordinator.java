package cn.lypi.tool;

import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.ApprovalKind;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.GranularApprovalPolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionGrantScope;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 协调 Codex 风格审批策略、交互 gate 和批准后的权限更新。
 */
public final class ApprovalCoordinator {
    private final PermissionGate permissionGate;
    private final PermissionUpdateStore permissionUpdateStore;
    private final RuntimePermissionRuleStore runtimePermissionRules;
    private final ApprovalRequestFactory requestFactory;

    public ApprovalCoordinator(
        PermissionGate permissionGate,
        PermissionUpdateStore permissionUpdateStore,
        List<PermissionRule> runtimePermissionRules,
        ApprovalRequestFactory requestFactory
    ) {
        this(
            permissionGate,
            permissionUpdateStore,
            new RuntimePermissionRuleStore(runtimePermissionRules),
            requestFactory
        );
    }

    public ApprovalCoordinator(
        PermissionGate permissionGate,
        PermissionUpdateStore permissionUpdateStore,
        RuntimePermissionRuleStore runtimePermissionRules,
        ApprovalRequestFactory requestFactory
    ) {
        this.permissionGate = permissionGate == null ? PermissionGate.denying() : permissionGate;
        this.permissionUpdateStore = permissionUpdateStore == null ? PermissionUpdateStore.noop() : permissionUpdateStore;
        this.runtimePermissionRules = Objects.requireNonNull(runtimePermissionRules, "runtimePermissionRules must not be null");
        this.requestFactory = requestFactory == null ? new ApprovalRequestFactory() : requestFactory;
    }

    /**
     * 解析一次工具权限决策。
     */
    public PermissionGateResult resolve(
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
        ApprovalDecision approvalDecision = evaluatePolicy(context, approvalKind(decision));
        if (approvalDecision.denied()) {
            return PermissionGateResult.deny(approvalDecision.reason());
        }
        PermissionGateResult result = permissionGate.request(request, tool, context, decision);
        PermissionGateResult safeResult = result == null ? PermissionGateResult.deny("权限请求未获允许。") : result;
        if (safeResult.status() == PermissionGateResult.Status.ALLOW) {
            safeResult.permissionUpdate().ifPresent(update -> applyPermissionUpdate(update, context));
        }
        return safeResult;
    }

    /**
     * 发起 additional permissions 审批。
     */
    public PermissionGateResult resolveAdditionalPermissions(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        ToolUseContext context,
        String reason,
        AdditionalPermissionProfile additionalPermissions
    ) {
        PermissionDecision decision = requestFactory.additionalPermissionsDecision(reason, additionalPermissions);
        return resolve(request, tool, context, decision);
    }

    private void applyPermissionUpdate(PermissionUpdate update, ToolUseContext context) {
        if (update == null) {
            return;
        }
        if (permissionUpdateStore instanceof PermissionAmendmentStore amendmentStore) {
            amendmentStore.appendPermissionUpdate(
                update,
                PermissionGrantScope.SESSION,
                context.sessionId(),
                turnId(context)
            );
        } else {
            permissionUpdateStore.append(update);
        }
        if (update.rule() != null) {
            runtimePermissionRules.add(context.sessionId(), update.rule());
        }
    }

    private String turnId(ToolUseContext context) {
        Object value = context.metadata().get("turnId");
        return value == null ? null : value.toString();
    }

    private ApprovalDecision evaluatePolicy(ToolUseContext context, ApprovalKind approvalKind) {
        ApprovalPolicy policy = runtimeState(context).approvalPolicy();
        ApprovalMode mode = approvalMode(policy, approvalKind);
        return switch (mode) {
            case ON_REQUEST, UNLESS_TRUSTED, ON_FAILURE -> ApprovalDecision.allow();
            case NEVER -> ApprovalDecision.deny(reasonName(approvalKind) + " approval is disabled by never policy");
            case GRANULAR -> ApprovalDecision.deny("nested granular approval mode is not supported");
        };
    }

    private ApprovalMode approvalMode(ApprovalPolicy policy, ApprovalKind approvalKind) {
        if (policy.mode() != ApprovalMode.GRANULAR) {
            return policy.mode();
        }
        GranularApprovalPolicy granularPolicy = policy.granularApprovalPolicy().orElseThrow();
        return switch (approvalKind == null ? ApprovalKind.COMMAND : approvalKind) {
            case REQUEST_PERMISSIONS -> granularPolicy.requestPermissions();
            case MCP_TOOL_CALL -> granularPolicy.mcpElicitations();
            case APPLY_PATCH -> granularPolicy.rules();
            case NETWORK -> granularPolicy.sandboxApproval();
            case COMMAND -> granularPolicy.rules();
        };
    }

    private PermissionRuntimeState runtimeState(ToolUseContext context) {
        Object value = context.metadata().get(ToolRuntimeContextFactory.METADATA_PERMISSION_RUNTIME_STATE);
        if (value instanceof PermissionRuntimeState runtimeState) {
            return runtimeState;
        }
        Object legacyValue = context.metadata().get(ToolRuntimeContextFactory.METADATA_PERMISSION_MODE);
        if (legacyValue instanceof PermissionMode permissionMode) {
            return PermissionRuntimeState.fromLegacy(permissionMode);
        }
        if (legacyValue instanceof String permissionMode && !permissionMode.isBlank()) {
            return PermissionRuntimeState.fromLegacy(PermissionMode.valueOf(permissionMode));
        }
        return PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE);
    }

    private ApprovalKind approvalKind(PermissionDecision decision) {
        Map<String, Object> metadata = decision.metadata() == null ? Map.of() : decision.metadata();
        Object value = metadata.get("approvalKind");
        if (value instanceof ApprovalKind approvalKind) {
            return approvalKind;
        }
        if (value instanceof String approvalKind && !approvalKind.isBlank()) {
            return ApprovalKind.valueOf(approvalKind);
        }
        return ApprovalKind.COMMAND;
    }

    private String reasonName(ApprovalKind approvalKind) {
        return switch (approvalKind == null ? ApprovalKind.COMMAND : approvalKind) {
            case REQUEST_PERMISSIONS -> "request_permissions";
            case MCP_TOOL_CALL -> "mcp elicitation";
            case NETWORK -> "network";
            case APPLY_PATCH -> "apply_patch";
            case COMMAND -> "command";
        };
    }

    private String decisionMessage(PermissionDecision decision) {
        if (decision == null || decision.message() == null || decision.message().isBlank()) {
            return "未提供原因。";
        }
        return decision.message();
    }

    private record ApprovalDecision(boolean denied, String reason) {
        private static ApprovalDecision allow() {
            return new ApprovalDecision(false, "");
        }

        private static ApprovalDecision deny(String reason) {
            return new ApprovalDecision(true, reason);
        }
    }
}
