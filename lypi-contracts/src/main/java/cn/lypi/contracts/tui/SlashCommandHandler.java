package cn.lypi.contracts.tui;

import java.util.Map;

@FunctionalInterface
public interface SlashCommandHandler {
    /**
     * TODO: 处理一次 slash command 调用。
     *
     * 状态变更必须通过 session entry 表达，不能只保存在 TUI 内存中。
     */
    void handle(Map<String, String> arguments);
}

