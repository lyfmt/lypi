package cn.lycode.tool.mcp;

import cn.lycode.contracts.common.ProgressSink;
import cn.lycode.contracts.tool.ToolUseContext;
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
