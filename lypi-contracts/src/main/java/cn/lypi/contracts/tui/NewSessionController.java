package cn.lypi.contracts.tui;

public interface NewSessionController {
    /**
     * 创建新的空 session 并返回切换后的 TUI 运行时状态。
     */
    SessionRuntimeState createNewSession();
}
