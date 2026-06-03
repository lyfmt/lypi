package cn.lypi.tool;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 创建工具调用上下文。
 *
 * NOTE: 该工厂集中处理 runtime options、模型上下文和单次 tool call 的上下文合并。
 */
public final class ToolRuntimeContextFactory {
    static final String METADATA_PERMISSION_MODE = "permissionMode";

    private final ToolRuntimeOptions options;

    public ToolRuntimeContextFactory(ToolRuntimeOptions options) {
        this.options = options == null ? ToolRuntimeOptions.defaults() : options;
    }

    /**
     * 为单次工具调用创建工具上下文。
     */
    public ToolUseContext create(ToolUseRequest request, ContextSnapshot context) {
        Objects.requireNonNull(request, "request must not be null");
        Map<String, Object> metadata = new LinkedHashMap<>();
        PermissionMode permissionMode = context == null ? PermissionMode.DEFAULT_EXECUTE : context.permissionMode();
        metadata.put(METADATA_PERMISSION_MODE, permissionMode);
        metadata.putAll(options.metadata());
        return new ToolUseContext(
            options.sessionId(),
            request.parentMessageId(),
            options.cwd(),
            Map.copyOf(metadata)
        );
    }
}
