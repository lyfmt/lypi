package cn.lypi.contracts.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record McpServerConfig(
    String name,
    McpTransport transport,
    List<String> command,
    Map<String, String> env,
    Duration startupTimeout,
    Duration callTimeout
) {}

