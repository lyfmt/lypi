package cn.lypi.contracts.hook;

public interface TurnHookRuntime {
    /**
     * 顺序执行 turn 执行前 hook 并返回合成决策。
     */
    BeforeTurnHookResult beforeTurn(BeforeTurnHookContext context);

    /**
     * 顺序执行 turn 执行后 hook。
     */
    void afterTurn(AfterTurnHookContext context);

    /**
     * 返回不执行任何 hook 的空实现。
     */
    static TurnHookRuntime noop() {
        return DefaultTurnHookRuntime.NOOP;
    }
}
