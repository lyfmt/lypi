package cn.lypi.contracts.subagent;

import java.util.Optional;

public record SubagentResultRef(
    String childSessionId,
    String finalEntryId,
    Optional<String> outputRef
) {
    public SubagentResultRef {
        outputRef = outputRef == null ? Optional.empty() : outputRef;
    }
}
