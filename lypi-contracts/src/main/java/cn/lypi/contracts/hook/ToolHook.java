package cn.lypi.contracts.hook;

import java.util.Objects;

public interface ToolHook {
    /**
     * 在工具执行前处理调用请求。
     *
     * NOTE: 默认不阻断工具执行。
     */
    default BeforeToolHookResult beforeToolCall(BeforeToolHookContext context) {
        Objects.requireNonNull(context, "context");
        return BeforeToolHookResult.allow();
    }

    /**
     * 在工具执行后处理工具结果。
     *
     * NOTE: 默认保留原始结果。
     */
    default AfterToolHookResult afterToolCall(AfterToolHookContext context) {
        Objects.requireNonNull(context, "context");
        return AfterToolHookResult.keep();
    }

    /**
     * 创建仅处理工具执行前阶段的 hook。
     */
    static ToolHook before(BeforeCallback callback) {
        BeforeCallback nonNullCallback = Objects.requireNonNull(callback, "callback");
        return new ToolHook() {
            @Override
            public BeforeToolHookResult beforeToolCall(BeforeToolHookContext context) {
                return Objects.requireNonNull(nonNullCallback.handle(context), "beforeToolCall result");
            }
        };
    }

    /**
     * 创建仅处理工具执行后阶段的 hook。
     */
    static ToolHook after(AfterCallback callback) {
        AfterCallback nonNullCallback = Objects.requireNonNull(callback, "callback");
        return new ToolHook() {
            @Override
            public AfterToolHookResult afterToolCall(AfterToolHookContext context) {
                return Objects.requireNonNull(nonNullCallback.handle(context), "afterToolCall result");
            }
        };
    }

    @FunctionalInterface
    interface BeforeCallback {
        /**
         * 处理工具执行前上下文并返回决策结果。
         */
        BeforeToolHookResult handle(BeforeToolHookContext context);
    }

    @FunctionalInterface
    interface AfterCallback {
        /**
         * 处理工具执行后上下文并返回结果处理决定。
         */
        AfterToolHookResult handle(AfterToolHookContext context);
    }
}
