package cn.lypi.tool;

import cn.lypi.contracts.hook.AfterToolHookContext;
import cn.lypi.contracts.hook.BeforeToolHookContext;
import cn.lypi.contracts.hook.BeforeToolHookResult;
import cn.lypi.contracts.hook.ToolHookRuntime;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.Map;
import java.util.Objects;

/**
 * 将稳定 hook runtime 适配为工具执行拦截器。
 */
public final class ToolHookExecutionInterceptor implements ToolExecutionInterceptor {
    private final ToolHookRuntime runtime;

    /**
     * 创建工具 hook 执行拦截器。
     *
     * NOTE: 当 runtime 为 null 时回退到 no-op 实现，保持默认工具执行语义不变。
     */
    public ToolHookExecutionInterceptor(ToolHookRuntime runtime) {
        this.runtime = runtime == null ? ToolHookRuntime.noop() : runtime;
    }

    @Override
    public BeforeResult beforeExecute(ToolUseRequest request, Tool<Map<String, Object>, ?> tool, ToolUseContext context) {
        ToolUseRequest nonNullRequest = Objects.requireNonNull(request, "request");
        Map<String, Object> canonicalInput = canonicalInput(nonNullRequest);
        BeforeToolHookResult result = runtime.beforeToolCall(new BeforeToolHookContext(
            nonNullRequest,
            Objects.requireNonNull(tool, "tool"),
            canonicalInput,
            Objects.requireNonNull(context, "context")
        ));
        if (result != null && result.blocked()) {
            return BeforeResult.block(result.message());
        }
        return BeforeResult.allow();
    }

    @Override
    public ToolResult<?> afterExecute(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        ToolUseContext context,
        ToolResult<?> result
    ) {
        ToolUseRequest nonNullRequest = Objects.requireNonNull(request, "request");
        Map<String, Object> canonicalInput = canonicalInput(nonNullRequest);
        return runtime.afterToolCall(new AfterToolHookContext(
            nonNullRequest,
            Objects.requireNonNull(tool, "tool"),
            canonicalInput,
            Objects.requireNonNull(context, "context"),
            Objects.requireNonNull(result, "result")
        )).orElse(result);
    }

    private Map<String, Object> canonicalInput(ToolUseRequest request) {
        return request.input() == null ? Map.of() : request.input();
    }
}
