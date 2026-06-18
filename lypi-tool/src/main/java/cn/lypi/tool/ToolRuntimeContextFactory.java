package cn.lypi.tool;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 创建工具调用上下文。
 *
 * NOTE: 该工厂集中处理 runtime options、模型上下文和单次 tool call 的上下文合并。
 */
public final class ToolRuntimeContextFactory {
    static final String METADATA_AGENT_MODE = "agentMode";
    static final String METADATA_PERMISSION_MODE = "permissionMode";
    static final String METADATA_PERMISSION_RUNTIME_STATE = "permissionRuntimeState";

    private final ToolRuntimeOptions options;

    public ToolRuntimeContextFactory(ToolRuntimeOptions options) {
        this.options = options == null ? ToolRuntimeOptions.defaults() : options;
    }

    public Path cwd() {
        return options.cwd();
    }

    /**
     * 为单次工具调用创建工具上下文。
     */
    public ToolUseContext create(ToolUseRequest request, ContextSnapshot context) {
        return create(request, context, null);
    }

    /**
     * 为带上层归属的单次工具调用创建工具上下文。
     */
    public ToolUseContext create(ToolUseRequest request, ContextSnapshot context, ToolRuntimeInvocation invocation) {
        Objects.requireNonNull(request, "request must not be null");
        Map<String, Object> metadata = new LinkedHashMap<>();
        AgentMode agentMode = context == null ? AgentMode.EXECUTE : context.mode();
        PermissionRuntimeState permissionRuntimeState = context == null
            ? PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE)
            : context.permissionRuntimeState();
        PermissionMode permissionMode = permissionRuntimeState.legacyPermissionMode();
        metadata.putAll(options.metadata());
        metadata.put(METADATA_AGENT_MODE, agentMode);
        metadata.put(METADATA_PERMISSION_RUNTIME_STATE, permissionRuntimeState);
        metadata.put(METADATA_PERMISSION_MODE, permissionMode);
        String turnId = invocation == null ? null : invocation.turnId();
        if (turnId != null && !turnId.isBlank()) {
            metadata.put("turnId", turnId);
        }
        String parentEntryId = invocation == null ? null : invocation.parentEntryId();
        if (parentEntryId != null && !parentEntryId.isBlank()) {
            metadata.put("parentEntryId", parentEntryId);
        }
        return new ToolUseContext(
            sessionId(invocation),
            request.parentMessageId(),
            options.cwd(),
            Map.copyOf(metadata)
        );
    }

    private String sessionId(ToolRuntimeInvocation invocation) {
        if (invocation == null || invocation.sessionId() == null || invocation.sessionId().isBlank()) {
            return options.sessionId();
        }
        return invocation.sessionId();
    }
}
