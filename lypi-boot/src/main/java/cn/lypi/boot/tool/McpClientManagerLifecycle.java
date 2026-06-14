package cn.lypi.boot.tool;

import cn.lypi.tool.mcp.McpClientManager;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理通过 boot 自动注册创建的 MCP client manager 生命周期。
 */
final class McpClientManagerLifecycle implements AutoCloseable {
    private final List<McpClientManager> managers = new ArrayList<>();

    synchronized void track(McpClientManager manager) {
        if (manager != null) {
            managers.add(manager);
        }
    }

    @Override
    public synchronized void close() {
        managers.forEach(McpClientManager::close);
        managers.clear();
    }
}
