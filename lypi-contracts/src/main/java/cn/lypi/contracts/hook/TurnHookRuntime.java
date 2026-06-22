package cn.lypi.contracts.hook;

import cn.lypi.contracts.agent.TurnState;
import java.util.Optional;

public interface TurnHookRuntime {
    /**
     * 顺序执行 turn 执行前 hook 并返回合成决策。
     */
    BeforeTurnHookResult beforeTurn(BeforeTurnHookContext context);

    /**
     * 顺序执行 turn 执行后 hook 并返回可选的最终替换状态。
     */
    Optional<TurnState> afterTurn(AfterTurnHookContext context);

    /**
     * 返回不执行任何 hook 的空实现。
     */
    static TurnHookRuntime noop() {
        return DefaultTurnHookRuntime.NOOP;
    }
}
