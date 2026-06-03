package cn.lypi.tool;

import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;
import java.util.Map;

/**
 * 工具执行拦截器工厂。
 */
public final class ToolExecutionInterceptors {
    private static final ToolExecutionInterceptor NOOP = new ToolExecutionInterceptor() {
        @Override
        public ToolExecutionInterceptor.BeforeResult beforeExecute(
            ToolUseRequest request,
            Tool<Map<String, Object>, ?> tool,
            ToolUseContext context
        ) {
            return ToolExecutionInterceptor.BeforeResult.allow();
        }

        @Override
        public ToolResult<?> afterExecute(
            ToolUseRequest request,
            Tool<Map<String, Object>, ?> tool,
            ToolUseContext context,
            ToolResult<?> result
        ) {
            return result;
        }
    };

    private ToolExecutionInterceptors() {
    }

    /**
     * 返回空操作拦截器。
     */
    public static ToolExecutionInterceptor noop() {
        return NOOP;
    }

    /**
     * 按列表顺序组合多个拦截器。
     */
    public static ToolExecutionInterceptor combine(List<ToolExecutionInterceptor> interceptors) {
        List<ToolExecutionInterceptor> safeInterceptors = interceptors == null ? List.of() : List.copyOf(interceptors);
        if (safeInterceptors.isEmpty()) {
            return noop();
        }
        return new ToolExecutionInterceptor() {
            @Override
            public ToolExecutionInterceptor.BeforeResult beforeExecute(
                ToolUseRequest request,
                Tool<Map<String, Object>, ?> tool,
                ToolUseContext context
            ) {
                for (ToolExecutionInterceptor interceptor : safeInterceptors) {
                    ToolExecutionInterceptor.BeforeResult result = interceptor.beforeExecute(request, tool, context);
                    if (result != null && result.blocked()) {
                        return result;
                    }
                }
                return ToolExecutionInterceptor.BeforeResult.allow();
            }

            @Override
            public ToolResult<?> afterExecute(
                ToolUseRequest request,
                Tool<Map<String, Object>, ?> tool,
                ToolUseContext context,
                ToolResult<?> result
            ) {
                ToolResult<?> current = result;
                for (ToolExecutionInterceptor interceptor : safeInterceptors) {
                    ToolResult<?> next = interceptor.afterExecute(request, tool, context, current);
                    if (next != null) {
                        current = next;
                    }
                }
                return current;
            }
        };
    }
}
