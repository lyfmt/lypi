package cn.lypi.tool;

import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.Map;
import java.util.Optional;

/**
 * 判定显式沙箱提权请求。
 */
final class SandboxEscalationPolicy {
    private static final String INPUT_SANDBOX_PERMISSIONS = "sandboxPermissions";
    private static final String INPUT_JUSTIFICATION = "justification";

    Optional<PermissionDecision> decide(ToolUseRequest request, ToolUseContext context) {
        if (!requiresEscalation(request)) {
            return Optional.empty();
        }
        AgentMode agentMode = agentMode(context);
        if (agentMode == AgentMode.PLAN) {
            return Optional.of(decision(
                PermissionBehavior.DENY,
                "AgentMode.PLAN 禁止沙箱提权执行。",
                Map.of("sandboxPermissions", "requireEscalated")
            ));
        }
        PermissionMode permissionMode = permissionMode(context);
        if (permissionMode == PermissionMode.BYPASS) {
            return Optional.of(decision(
                PermissionBehavior.ALLOW,
                "BYPASS 权限模式允许沙箱提权执行。",
                Map.of("sandboxPermissions", "requireEscalated")
            ));
        }
        String justification = stringInput(request.input(), INPUT_JUSTIFICATION);
        return Optional.of(decision(
            PermissionBehavior.ASK,
            "沙箱提权执行请求: " + justification,
            Map.of(
                "sandboxPermissions", "requireEscalated",
                "justification", justification
            )
        ));
    }

    private boolean requiresEscalation(ToolUseRequest request) {
        return "requireEscalated".equals(stringInput(request.input(), INPUT_SANDBOX_PERMISSIONS));
    }

    private AgentMode agentMode(ToolUseContext context) {
        Object value = context.metadata().get(ToolRuntimeContextFactory.METADATA_AGENT_MODE);
        if (value instanceof AgentMode agentMode) {
            return agentMode;
        }
        if (value instanceof String agentMode) {
            return AgentMode.valueOf(agentMode);
        }
        return AgentMode.EXECUTE;
    }

    private PermissionMode permissionMode(ToolUseContext context) {
        Object value = context.metadata().get(ToolRuntimeContextFactory.METADATA_PERMISSION_MODE);
        if (value instanceof PermissionMode permissionMode) {
            return permissionMode;
        }
        if (value instanceof String permissionMode) {
            return PermissionMode.valueOf(permissionMode);
        }
        return PermissionMode.DEFAULT_EXECUTE;
    }

    private String stringInput(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private PermissionDecision decision(PermissionBehavior behavior, String message, Map<String, Object> metadata) {
        return new PermissionDecision(
            behavior,
            PermissionDecisionReason.MODE_DEFAULT,
            message,
            Optional.<PermissionUpdate>empty(),
            metadata
        );
    }
}
