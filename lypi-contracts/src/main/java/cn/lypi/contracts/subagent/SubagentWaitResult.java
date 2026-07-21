package cn.lypi.contracts.subagent;

import java.util.Optional;

public record SubagentWaitResult(
    SubagentWaitOutcome outcome,
    Optional<String> taskName,
    Optional<String> agentId,
    Optional<String> childSessionId,
    Optional<String> runId,
    Optional<SubagentRunStatus> status,
    Optional<String> content
) {
    public SubagentWaitResult {
        outcome = outcome == null ? SubagentWaitOutcome.TIMED_OUT : outcome;
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
            SubagentWaitOutcome.COMPLETED,
            Optional.ofNullable(taskName),
            Optional.ofNullable(agentId),
            Optional.ofNullable(childSessionId),
            Optional.ofNullable(runId),
            Optional.ofNullable(status),
            Optional.ofNullable(content)
        );
    }

    public static SubagentWaitResult steered() {
        return empty(SubagentWaitOutcome.STEERED);
    }

    public static SubagentWaitResult aborted() {
        return empty(SubagentWaitOutcome.ABORTED);
    }

    public static SubagentWaitResult timedOut() {
        return empty(SubagentWaitOutcome.TIMED_OUT);
    }

    public boolean received() {
        return outcome == SubagentWaitOutcome.COMPLETED;
    }

    private static SubagentWaitResult empty(SubagentWaitOutcome outcome) {
        return new SubagentWaitResult(
            outcome,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }
}
