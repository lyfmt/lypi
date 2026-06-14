package cn.lypi.tool.mcp;

import java.nio.file.Path;

@FunctionalInterface
public interface McpClientManagerFactory {
    /**
     * 创建绑定到指定 cwd 的 MCP client manager。
     */
    McpClientManager create(Path cwd);
}
