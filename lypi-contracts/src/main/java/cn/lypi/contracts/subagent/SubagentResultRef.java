package cn.lypi.contracts.subagent;

import java.util.Optional;

public record SubagentResultRef(
    String childSessionId,
    String finalEntryId,
    Optional<String> outputRef,
    Optional<SubagentRunStatus> status
) {
    public SubagentResultRef(String childSessionId, String finalEntryId, Optional<String> outputRef) {
        this(childSessionId, finalEntryId, outputRef, Optional.empty());
    }

    public SubagentResultRef {
        outputRef = outputRef == null ? Optional.empty() : outputRef;
        status = status == null ? Optional.empty() : status;
    }
}
