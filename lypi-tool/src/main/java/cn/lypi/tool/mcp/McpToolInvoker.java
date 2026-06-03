package cn.lypi.tool.mcp;

import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.Map;

@FunctionalInterface
public interface McpToolInvoker {
    /**
     * 调用一个 MCP tool。
     */
    Object invoke(
        String serverName,
        String toolName,
        Map<String, Object> arguments,
        ToolUseContext context,
        ProgressSink progress
    );
}
