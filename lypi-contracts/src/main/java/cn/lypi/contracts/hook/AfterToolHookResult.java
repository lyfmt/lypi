package cn.lypi.contracts.hook;

import cn.lypi.contracts.tool.ToolResult;
import java.util.Objects;
import java.util.Optional;

/**
 * 表示工具执行后 hook 的处理结果。
 */
public record AfterToolHookResult(
    Optional<ToolResult<?>> replacement
) {
    public AfterToolHookResult {
        replacement = replacement == null ? Optional.empty() : replacement;
    }

    /**
     * 返回保留原始结果的处理结果。
     */
    public static AfterToolHookResult keep() {
        return new AfterToolHookResult(Optional.empty());
    }

    /**
     * 返回替换工具结果的处理结果。
     */
    public static AfterToolHookResult replace(ToolResult<?> result) {
        return new AfterToolHookResult(Optional.of(Objects.requireNonNull(result, "result")));
    }
}
