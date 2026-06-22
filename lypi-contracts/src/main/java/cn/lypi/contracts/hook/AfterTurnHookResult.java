package cn.lypi.contracts.hook;

import cn.lypi.contracts.agent.TurnState;
import java.util.Objects;
import java.util.Optional;

/**
 * 表示 turn 执行后 hook 的处理结果。
 */
public record AfterTurnHookResult(
    Optional<TurnState> replacement
) {
    public AfterTurnHookResult {
        replacement = replacement == null ? Optional.empty() : replacement;
    }

    /**
     * 返回保留原始 turn 状态的处理结果。
     */
    public static AfterTurnHookResult keep() {
        return new AfterTurnHookResult(Optional.empty());
    }

    /**
     * 返回替换 turn 状态的处理结果。
     */
    public static AfterTurnHookResult replace(TurnState state) {
        return new AfterTurnHookResult(Optional.of(Objects.requireNonNull(state, "state")));
    }
}
