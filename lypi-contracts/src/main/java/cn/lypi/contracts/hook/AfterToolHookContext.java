package cn.lypi.contracts.hook;

import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.Map;
import java.util.Objects;

/**
 * 表示工具执行后 hook 的上下文。
 */
public record AfterToolHookContext(
    ToolUseRequest request,
    Tool<?, ?> tool,
    Map<String, Object> input,
    ToolUseContext toolContext,
    ToolResult<?> result
) {
    public AfterToolHookContext {
        request = Objects.requireNonNull(request, "request");
        tool = Objects.requireNonNull(tool, "tool");
        input = Map.copyOf(Objects.requireNonNull(input, "input"));
        request = new ToolUseRequest(
            request.toolUseId(),
            request.toolName(),
            input,
            request.parentMessageId()
        );
        toolContext = Objects.requireNonNull(toolContext, "toolContext");
        result = Objects.requireNonNull(result, "result");
    }
}
