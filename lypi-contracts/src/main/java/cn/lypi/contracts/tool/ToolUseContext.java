package cn.lypi.contracts.tool;

import java.nio.file.Path;
import java.util.Map;

public record ToolUseContext(
    String sessionId,
    String messageId,
    Path cwd,
    Map<String, Object> metadata
) {}

