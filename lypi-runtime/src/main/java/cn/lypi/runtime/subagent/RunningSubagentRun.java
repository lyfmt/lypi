package cn.lypi.runtime.subagent;

public record RunningSubagentRun(
    String runId,
    String lifecycleEntryId,
    SubagentAgent agent,
    SubagentProcessHandle handle
) {}
