package cn.lypi.contracts.subagent;

import java.util.Optional;

public record SubagentWaitResult(
    boolean received,
    Optional<String> taskName,
    Optional<String> agentId,
    Optional<String> childSessionId,
    Optional<String> runId,
    Optional<SubagentRunStatus> status,
    Optional<String> content
) {
    public SubagentWaitResult {
        taskName = taskName == null ? Optional.empty() : taskName;
        agentId = agentId == null ? Optional.empty() : agentId;
        childSessionId = childSessionId == null ? Optional.empty() : childSessionId;
        runId = runId == null ? Optional.empty() : runId;
        status = status == null ? Optional.empty() : status;
        content = content == null ? Optional.empty() : content;
    }

    public static SubagentWaitResult completed(
        String taskName,
        String agentId,
        String childSessionId,
        String runId,
        SubagentRunStatus status,
        String content
    ) {
        return new SubagentWaitResult(
            true,
            Optional.ofNullable(taskName),
            Optional.ofNullable(agentId),
            Optional.ofNullable(childSessionId),
            Optional.ofNullable(runId),
            Optional.ofNullable(status),
            Optional.ofNullable(content)
        );
    }

    public static SubagentWaitResult timedOut() {
        return new SubagentWaitResult(
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }
}
