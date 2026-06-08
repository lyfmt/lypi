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
     * 请求退出当前 TUI 会话。
     */
    default void requestExit(String reason) {
    }
}
