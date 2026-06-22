package cn.lypi.contracts.hook;

/**
 * 表示工具执行前 hook 的决策结果。
 */
public record BeforeToolHookResult(
    boolean blocked,
    String message
) {
    /**
     * 返回允许继续执行的结果。
     */
    public static BeforeToolHookResult allow() {
        return new BeforeToolHookResult(false, null);
    }

    /**
     * 返回阻断工具执行的结果。
     */
    public static BeforeToolHookResult block(String message) {
        return new BeforeToolHookResult(true, message);
    }
}
