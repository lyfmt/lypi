package cn.lypi.contracts.tui;

import java.util.Map;

@FunctionalInterface
public interface SlashCommandHandler {
    /**
     * 处理一次 slash command 调用。
     *
     * NOTE: 状态变更必须通过 session entry 表达，不能只保存在 TUI 内存中。
     */
    void handle(Map<String, String> arguments);

    /**
     * 返回最近一次 slash command 的用户可见输出。
     */
    default String lastOutput() {
        return "";
    }
}
