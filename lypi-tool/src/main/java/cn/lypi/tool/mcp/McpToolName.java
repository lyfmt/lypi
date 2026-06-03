package cn.lypi.tool.mcp;

public final class McpToolName {
    private McpToolName() {
    }

    /**
     * 生成 MCP 工具在本地注册表中的名称。
     */
    public static String format(String serverName, String toolName) {
        return "mcp__" + normalize(serverName) + "__" + normalize(toolName);
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9_]+", "_");
        normalized = normalized.replaceAll("_+", "_").replaceAll("^_|_$", "");
        return normalized.isBlank() ? "unknown" : normalized;
    }
}
