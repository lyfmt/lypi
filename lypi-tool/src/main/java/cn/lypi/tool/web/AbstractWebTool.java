package cn.lypi.tool.web;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.security.NetworkPolicyMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract class AbstractWebTool implements Tool<Map<String, Object>, String> {
    private static final int DEFAULT_MAX_RESULT_SIZE = 16_384;

    @Override
    public List<String> aliases() {
        return List.of();
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of("type", "object", "properties", Map.of()));
    }

    @Override
    public InterruptBehavior interruptBehavior() {
        return InterruptBehavior.CANCEL;
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
        return true;
    }

    @Override
    public boolean isDestructive(Map<String, Object> input) {
        return false;
    }

    @Override
    public int maxResultSize() {
        return DEFAULT_MAX_RESULT_SIZE;
    }

    @Override
    public AgentMessage serializeForContext(String output) {
        return WebToolMessages.serializeForContext(output);
    }

    protected ToolResult<String> success(ToolUseContext context, String text) {
        return WebToolMessages.success(toolUseId(context), text);
    }

    protected ToolResult<String> error(ToolUseContext context, String message) {
        return WebToolMessages.error(toolUseId(context), message);
    }

    protected String toolUseId(ToolUseContext context) {
        return WebToolMessages.toolUseId(context);
    }

    protected PermissionDecision networkDecision(ToolUseContext context, String toolName, Map<String, Object> metadata) {
        PermissionRuntimeState state = permissionRuntimeState(context);
        NetworkPolicyMode networkMode = state.permissionProfile().network().mode();
        if (networkMode == NetworkPolicyMode.ENABLED) {
            return new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                toolName + " 网络访问已由当前权限 profile 允许。",
                Optional.<PermissionUpdate>empty(),
                withNetworkMode(metadata, networkMode)
            );
        }
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.TOOL_SPECIFIC,
            toolName + " 需要访问外部网络。",
            Optional.<PermissionUpdate>empty(),
            withNetworkMode(metadata, networkMode)
        );
    }

    private PermissionRuntimeState permissionRuntimeState(ToolUseContext context) {
        Object value = context.metadata().get("permissionRuntimeState");
        if (value instanceof PermissionRuntimeState state) {
            return state;
        }
        return PermissionRuntimeState.fromLegacy(cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE);
    }

    private Map<String, Object> withNetworkMode(Map<String, Object> metadata, NetworkPolicyMode networkMode) {
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
        if (metadata != null) {
            copy.putAll(metadata);
        }
        copy.put("networkMode", networkMode.name());
        return Map.copyOf(copy);
    }
}
