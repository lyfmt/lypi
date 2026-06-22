package cn.lypi.contracts.hook;

/**
 * 表示 turn 执行后 hook 的处理结果。
 */
public record AfterTurnHookResult() {
    /**
     * 返回保留原始 turn 状态的处理结果。
     */
    public static AfterTurnHookResult keep() {
        return new AfterTurnHookResult();
    }
}
