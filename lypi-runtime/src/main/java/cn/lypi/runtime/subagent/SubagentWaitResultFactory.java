package cn.lypi.runtime.subagent;

import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import cn.lypi.contracts.subagent.SubagentWaitResult;
import java.util.Optional;

final class SubagentWaitResultFactory {
    private SubagentWaitResultFactory() {
    }

    static SubagentWaitResult fromOutput(String agentId, String runId, HeadlessSubagentOutput output) {
        return new SubagentWaitResult(
            agentId,
            output.childSessionId(),
            runId,
            output.status(),
            optionalNonBlank(output.summary()),
            output.finalEntryId(),
            output.errorMessage()
        );
    }

    static SubagentWaitResult timedOut(DefaultAgentCenter.RunningAgent running) {
        return new SubagentWaitResult(
            running.agentId(),
            running.childSessionId(),
            running.parentSpawnEntryId(),
            SubagentRunStatus.TIMED_OUT,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    static SubagentWaitResult interrupted(DefaultAgentCenter.RunningAgent running) {
        return new SubagentWaitResult(
            running.agentId(),
            running.childSessionId(),
            running.parentSpawnEntryId(),
            SubagentRunStatus.INTERRUPTED,
            Optional.empty(),
            Optional.empty(),
            Optional.of("Interrupted while waiting for subagent")
        );
    }

    static SubagentWaitResult failed(DefaultAgentCenter.RunningAgent running, Exception exception) {
        return new SubagentWaitResult(
            running.agentId(),
            running.childSessionId(),
            running.parentSpawnEntryId(),
            SubagentRunStatus.FAILED,
            Optional.empty(),
            Optional.empty(),
            Optional.ofNullable(exception.getMessage())
        );
    }

    static SubagentWaitResult failed(SubagentWaitRequest request, String message) {
        return new SubagentWaitResult(
            request.agentId().orElse(""),
            request.childSessionId().orElse(""),
            request.runId().orElse(""),
            SubagentRunStatus.FAILED,
            Optional.empty(),
            Optional.empty(),
            Optional.of(message)
        );
    }

    private static Optional<String> optionalNonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
