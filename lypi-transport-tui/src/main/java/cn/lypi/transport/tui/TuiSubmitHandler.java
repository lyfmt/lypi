package cn.lypi.transport.tui;

interface TuiSubmitHandler {
    /**
     * 提交一轮用户输入。
     */
    void submitUserInput(String input);

    /**
     * 请求中断当前活跃 turn。
     */
    void requestInterrupt(String reason);

    /**
     * 提交权限请求的用户选项。
     */
    default void submitPermissionOption(String toolUseId, String optionId) {
    }

    /**
     * 请求退出当前 TUI 会话。
     */
    default void requestExit(String reason) {
    }
}
