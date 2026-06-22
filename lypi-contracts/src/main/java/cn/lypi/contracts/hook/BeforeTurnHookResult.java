package cn.lypi.contracts.hook;

/**
 * 表示 turn 执行前 hook 的决策结果。
 */
public record BeforeTurnHookResult(
    boolean blocked,
    String message
) {
    /**
     * 返回允许继续执行的结果。
     */
    public static BeforeTurnHookResult allow() {
        return new BeforeTurnHookResult(false, null);
    }

    /**
     * 返回阻断 turn 执行的结果。
     */
    public static BeforeTurnHookResult block(String message) {
        return new BeforeTurnHookResult(true, message);
    }
}
