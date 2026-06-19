package cn.lypi.tool;

import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.ApprovalKind;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionOptionPolicy;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 创建结构化审批请求。
 *
 * NOTE: 该工厂只组装跨模块事件协议，不执行权限判定或用户交互。
 */
public final class ApprovalRequestFactory {
    private static final String METADATA_APPROVAL_KIND = "approvalKind";
    private static final String METADATA_ADDITIONAL_PERMISSIONS = "additionalPermissions";
    private static final String METADATA_INCLUDE_SESSION_APPROVAL = "includeSessionApproval";
    private static final String METADATA_STRICT_AUTO_REVIEW = "strictAutoReview";

    /**
     * 创建工具 ASK 决策对应的权限请求事件。
     */
    public PermissionRequestEvent create(
        ToolUseRequest request,
        Tool<?, ?> tool,
        ToolUseContext context,
        PermissionDecision decision
    ) {
        PermissionDecision safeDecision = decision == null ? legacyDecision("权限请求需要确认。") : decision;
        Map<String, Object> decisionMetadata = safeDecision.metadata() == null ? Map.of() : safeDecision.metadata();
        ApprovalKind approvalKind = approvalKind(decisionMetadata);
        Optional<AdditionalPermissionProfile> additionalPermissions = additionalPermissions(decisionMetadata);
        PermissionOptionPolicy.Options optionPolicy = optionPolicy(safeDecision, approvalKind, additionalPermissions);
        String message = decisionMessage(safeDecision);
        String toolName = tool == null ? safeToolName(request) : tool.name();
        return new PermissionRequestEvent(
            context.sessionId(),
            requestId(request),
            request.toolUseId(),
            toolName,
            message,
            renderToolUse(request, tool),
            message,
            safeDecision,
            approvalKind,
            optionPolicy.reviewDecisions(),
            additionalPermissions,
            strictAutoReview(decisionMetadata),
            optionPolicy.options(),
            optionPolicy.defaultOptionId(),
            optionPolicy.cancelOptionId(),
            metadata(toolName, approvalKind, decisionMetadata),
            Instant.now()
        );
    }

    /**
     * 创建 additional permissions 审批使用的 ASK 决策。
     */
    public PermissionDecision additionalPermissionsDecision(String reason, AdditionalPermissionProfile additionalPermissions) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_APPROVAL_KIND, ApprovalKind.REQUEST_PERMISSIONS);
        metadata.put(METADATA_ADDITIONAL_PERMISSIONS, normalize(additionalPermissions));
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.TOOL_SPECIFIC,
            blankToDefault(reason, "请求额外权限。"),
            Optional.<PermissionUpdate>empty(),
            Map.copyOf(metadata)
        );
    }

    private PermissionOptionPolicy.Options optionPolicy(
        PermissionDecision decision,
        ApprovalKind approvalKind,
        Optional<AdditionalPermissionProfile> additionalPermissions
    ) {
        Map<String, Object> metadata = decision.metadata() == null ? Map.of() : decision.metadata();
        if (approvalKind == ApprovalKind.REQUEST_PERMISSIONS || additionalPermissions.isPresent()) {
            return PermissionOptionPolicy.forAdditionalPermissionsApproval();
        }
        if (metadata.containsKey(METADATA_APPROVAL_KIND)) {
            return PermissionOptionPolicy.forApproval(
                approvalKind,
                decision.suggestedUpdate(),
                booleanMetadata(metadata, METADATA_INCLUDE_SESSION_APPROVAL)
            );
        }
        return PermissionOptionPolicy.fromDecision(decision);
    }

    private Map<String, Object> metadata(
        String toolName,
        ApprovalKind approvalKind,
        Map<String, Object> decisionMetadata
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", toolName);
        metadata.put(METADATA_APPROVAL_KIND, approvalKind);
        metadata.putAll(decisionMetadata);
        return Map.copyOf(metadata);
    }

    @SuppressWarnings("unchecked")
    private String renderToolUse(ToolUseRequest request, Tool<?, ?> tool) {
        if (tool == null) {
            return safeToolName(request) + " " + safeInput(request);
        }
        Tool<Map<String, Object>, ?> typedTool = (Tool<Map<String, Object>, ?>) tool;
        return typedTool.renderForUser(safeInput(request));
    }

    private Map<String, Object> safeInput(ToolUseRequest request) {
        return request.input() == null ? Map.of() : request.input();
    }

    private String requestId(ToolUseRequest request) {
        return "perm_" + request.toolUseId();
    }

    private String safeToolName(ToolUseRequest request) {
        return request == null || request.toolName() == null || request.toolName().isBlank()
            ? "unknown"
            : request.toolName();
    }

    private PermissionDecision legacyDecision(String message) {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.TOOL_SPECIFIC,
            message,
            Optional.<PermissionUpdate>empty(),
            Map.of()
        );
    }

    private ApprovalKind approvalKind(Map<String, Object> metadata) {
        Object value = metadata.get(METADATA_APPROVAL_KIND);
        if (value instanceof ApprovalKind approvalKind) {
            return approvalKind;
        }
        if (value instanceof String approvalKind && !approvalKind.isBlank()) {
            return ApprovalKind.valueOf(approvalKind);
        }
        return ApprovalKind.COMMAND;
    }

    private Optional<AdditionalPermissionProfile> additionalPermissions(Map<String, Object> metadata) {
        Object value = metadata.get(METADATA_ADDITIONAL_PERMISSIONS);
        return value instanceof AdditionalPermissionProfile additionalPermissions
            ? Optional.of(additionalPermissions)
            : Optional.empty();
    }

    private boolean strictAutoReview(Map<String, Object> metadata) {
        return booleanMetadata(metadata, METADATA_STRICT_AUTO_REVIEW);
    }

    private boolean booleanMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof String flag && Boolean.parseBoolean(flag);
    }

    private AdditionalPermissionProfile normalize(AdditionalPermissionProfile additionalPermissions) {
        return additionalPermissions == null ? AdditionalPermissionProfile.empty() : additionalPermissions;
    }

    private String decisionMessage(PermissionDecision decision) {
        return blankToDefault(decision.message(), "权限请求需要确认。");
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
