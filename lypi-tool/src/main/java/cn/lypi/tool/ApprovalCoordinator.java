package cn.lypi.tool;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
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

/**
 * 协调 Codex 风格审批策略、交互 gate 和批准后的权限更新。
 */
public final class ApprovalCoordinator {
    private final PermissionGate permissionGate;
    private final PermissionUpdateStore permissionUpdateStore;
    private final RuntimePermissionRuleStore runtimePermissionRules;
    private final ApprovalRequestFactory requestFactory;
    private final PermissionReviewer permissionReviewer;

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
            requestFactory,
            PermissionReviewer.denying()
        );
    }

    public ApprovalCoordinator(
        PermissionGate permissionGate,
        PermissionUpdateStore permissionUpdateStore,
        RuntimePermissionRuleStore runtimePermissionRules,
        ApprovalRequestFactory requestFactory
    ) {
        this(
            permissionGate,
            permissionUpdateStore,
            runtimePermissionRules,
            requestFactory,
            PermissionReviewer.denying()
        );
    }

    ApprovalCoordinator(
        PermissionGate permissionGate,
        PermissionUpdateStore permissionUpdateStore,
        RuntimePermissionRuleStore runtimePermissionRules,
        ApprovalRequestFactory requestFactory,
        PermissionReviewer permissionReviewer
    ) {
        this.permissionGate = permissionGate == null ? PermissionGate.denying() : permissionGate;
        this.permissionUpdateStore = permissionUpdateStore == null ? PermissionUpdateStore.noop() : permissionUpdateStore;
        this.runtimePermissionRules = Objects.requireNonNull(runtimePermissionRules, "runtimePermissionRules must not be null");
        this.requestFactory = requestFactory == null ? new ApprovalRequestFactory() : requestFactory;
        this.permissionReviewer = permissionReviewer == null ? PermissionReviewer.denying() : permissionReviewer;
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
        return resolve(request, tool, context, null, decision);
    }

    public PermissionGateResult resolve(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        ToolUseContext context,
        ContextSnapshot contextSnapshot,
        PermissionDecision decision
    ) {
        PermissionMode mode = runtimeState(context).mode();
        if (mode == PermissionMode.BYPASS) {
            return PermissionGateResult.allow();
        }
        PermissionGateResult result = switch (mode) {
            case ASK -> permissionGate.request(request, tool, context, decision);
            case AUTO -> review(request, tool, context, contextSnapshot, decision);
            case BYPASS -> PermissionGateResult.allow();
        };
        PermissionGateResult safeResult = result == null ? PermissionGateResult.deny("权限请求未获允许。") : result;
        if (safeResult.status() == PermissionGateResult.Status.ALLOW) {
            safeResult.permissionUpdate().ifPresent(update -> applyPermissionUpdate(update, context));
        }
        return safeResult;
    }

    private PermissionGateResult review(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        ToolUseContext context,
        ContextSnapshot contextSnapshot,
        PermissionDecision decision
    ) {
        try {
            return permissionReviewer.review(request, tool, context, contextSnapshot, decision);
        } catch (RuntimeException exception) {
            return PermissionGateResult.deny("AUTO 权限复核失败: " + exception.getMessage());
        }
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
        return resolveAdditionalPermissions(request, tool, context, null, reason, additionalPermissions);
    }

    public PermissionGateResult resolveAdditionalPermissions(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        ToolUseContext context,
        ContextSnapshot contextSnapshot,
        String reason,
        AdditionalPermissionProfile additionalPermissions
    ) {
        PermissionDecision decision = requestFactory.additionalPermissionsDecision(reason, additionalPermissions);
        return resolve(request, tool, context, contextSnapshot, decision);
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
            return PermissionRuntimeState.fromLegacy(PermissionMode.fromJson(permissionMode));
        }
        return PermissionRuntimeState.forMode(PermissionMode.ASK);
    }

}
