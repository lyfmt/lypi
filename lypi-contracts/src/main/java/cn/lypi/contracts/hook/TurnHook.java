package cn.lypi.contracts.hook;

import java.util.Objects;

public interface TurnHook {
    /**
     * 返回 hook 展示名称。
     */
    default String name() {
        return getClass().getName();
    }

    /**
     * 在 turn 执行前处理请求。
     *
     * NOTE: 默认不阻断 turn 执行。
     */
    default BeforeTurnHookResult beforeTurn(BeforeTurnHookContext context) {
        Objects.requireNonNull(context, "context");
        return BeforeTurnHookResult.allow();
    }

    /**
     * 在 turn 执行后处理最终状态。
     *
     * NOTE: 默认保留原始状态。
     */
    default AfterTurnHookResult afterTurn(AfterTurnHookContext context) {
        Objects.requireNonNull(context, "context");
        return AfterTurnHookResult.keep();
    }

    /**
     * 创建仅处理 turn 执行前阶段的 hook。
     */
    static TurnHook before(BeforeCallback callback) {
        BeforeCallback nonNullCallback = Objects.requireNonNull(callback, "callback");
        return new TurnHook() {
            @Override
            public BeforeTurnHookResult beforeTurn(BeforeTurnHookContext context) {
                return Objects.requireNonNull(nonNullCallback.handle(context), "beforeTurn result");
            }
        };
    }

    /**
     * 创建仅处理 turn 执行后阶段的 hook。
     */
    static TurnHook after(AfterCallback callback) {
        AfterCallback nonNullCallback = Objects.requireNonNull(callback, "callback");
        return new TurnHook() {
            @Override
            public AfterTurnHookResult afterTurn(AfterTurnHookContext context) {
                return Objects.requireNonNull(nonNullCallback.handle(context), "afterTurn result");
            }
        };
    }

    @FunctionalInterface
    interface BeforeCallback {
        /**
         * 处理 turn 执行前上下文并返回决策结果。
         */
        BeforeTurnHookResult handle(BeforeTurnHookContext context);
    }

    @FunctionalInterface
    interface AfterCallback {
        /**
         * 观察 turn 执行后上下文并返回处理完成结果。
         */
        AfterTurnHookResult handle(AfterTurnHookContext context);
    }
}
