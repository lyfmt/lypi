package cn.lypi.contracts.hook;

import cn.lypi.contracts.tool.ToolResult;
import java.util.Optional;

public interface ToolHookRuntime {
    /**
     * 顺序执行工具执行前 hook 并返回合成决策。
     */
    BeforeToolHookResult beforeToolCall(BeforeToolHookContext context);

    /**
     * 顺序执行工具执行后 hook 并返回可选的最终替换结果。
     */
    Optional<ToolResult<?>> afterToolCall(AfterToolHookContext context);

    /**
     * 返回不执行任何 hook 的空实现。
     */
    static ToolHookRuntime noop() {
        return DefaultToolHookRuntime.NOOP;
    }
}
