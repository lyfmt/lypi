package cn.lycode.contracts.tool;

import java.nio.file.Path;
import java.util.Map;

public record ToolUseContext(
    String sessionId,
    String messageId,
    Path workspaceRoot,
    Path cwd,
    Map<String, Object> metadata
) {
    public ToolUseContext(String sessionId, String messageId, Path cwd, Map<String, Object> metadata) {
        this(sessionId, messageId, cwd, cwd, metadata);
    }
}
