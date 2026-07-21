package cn.lypi.runtime.subagent;

public record RunningAgentSnapshot(
    String agentId,
    String taskName,
    String childSessionId,
    String runId,
    String parentSessionId,
    String parentSpawnEntryId
) {}
