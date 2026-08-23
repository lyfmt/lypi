package cn.lycode.runtime.subagent;

public record RunningSubagentRun(
    String runId,
    SubagentAgent agent,
    SubagentProcessHandle handle
) {}
