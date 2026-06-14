package cn.lypi.tool;

import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.Map;

/**
 * 工具执行拦截器。
 *
 * NOTE: 拦截器用于 runtime 内部扩展，不直接绑定事件、审计或插件实现。
 */
public interface ToolExecutionInterceptor {
    /**
     * 在工具执行前检查是否允许继续。
     */
    BeforeResult beforeExecute(ToolUseRequest request, Tool<Map<String, Object>, ?> tool, ToolUseContext context);

    /**
     * 在工具执行后改写或保留结果。
     */
    ToolResult<?> afterExecute(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        ToolUseContext context,
        ToolResult<?> result
    );

    /**
     * 创建只处理 before 阶段的拦截器。
     */
    static ToolExecutionInterceptor before(BeforeCallback callback) {
        return new ToolExecutionInterceptor() {
            @Override
            public BeforeResult beforeExecute(
                ToolUseRequest request,
                Tool<Map<String, Object>, ?> tool,
                ToolUseContext context
            ) {
                return callback.beforeExecute(request, tool, context);
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
    }

    /**
     * 创建只处理 after 阶段的拦截器。
     */
    static ToolExecutionInterceptor after(AfterCallback callback) {
        return new ToolExecutionInterceptor() {
            @Override
            public BeforeResult beforeExecute(
                ToolUseRequest request,
                Tool<Map<String, Object>, ?> tool,
                ToolUseContext context
            ) {
                return BeforeResult.allow();
            }

            @Override
            public ToolResult<?> afterExecute(
                ToolUseRequest request,
                Tool<Map<String, Object>, ?> tool,
                ToolUseContext context,
                ToolResult<?> result
            ) {
                return callback.afterExecute(request, tool, context, result);
            }
        };
    }

    @FunctionalInterface
    interface BeforeCallback {
        BeforeResult beforeExecute(ToolUseRequest request, Tool<Map<String, Object>, ?> tool, ToolUseContext context);
    }

    @FunctionalInterface
    interface AfterCallback {
        ToolResult<?> afterExecute(
            ToolUseRequest request,
            Tool<Map<String, Object>, ?> tool,
            ToolUseContext context,
            ToolResult<?> result
        );
    }

    record BeforeResult(boolean blocked, String message) {
        public static BeforeResult allow() {
            return new BeforeResult(false, "");
        }

        public static BeforeResult block(String message) {
            return new BeforeResult(true, message == null ? "" : message);
        }
    }
}
