package cn.lypi.contracts.mcp;

import java.time.Instant;
import java.util.List;

public record McpClientSession(
    String serverName,
    McpConnectionState state,
    List<McpToolSchema> tools,
    Instant connectedAt
) {}

