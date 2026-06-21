package cn.lypi.contracts.hook;

import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.Map;
import java.util.Objects;

/**
 * 表示工具执行前 hook 的上下文。
 */
public record BeforeToolHookContext(
    ToolUseRequest request,
    Tool<?, ?> tool,
    Map<String, Object> input,
    ToolUseContext toolContext
) {
    public BeforeToolHookContext {
        request = Objects.requireNonNull(request, "request");
        tool = Objects.requireNonNull(tool, "tool");
        input = Map.copyOf(Objects.requireNonNull(input, "input"));
        toolContext = Objects.requireNonNull(toolContext, "toolContext");
    }
}
