package cn.lypi.runtime.subagent;

import java.nio.file.Path;

public record SubagentAgent(
    String agentId,
    String taskName,
    String childSessionId,
    String parentSessionId,
    String parentSpawnEntryId,
    Path parentCwd
) {}
