package cn.lypi.contracts.subagent;

import java.util.List;

public record SubagentToolPolicy(
    List<String> requestedTools,
    List<String> effectiveTools
) {
    public SubagentToolPolicy {
        requestedTools = requestedTools == null ? List.of() : List.copyOf(requestedTools);
        effectiveTools = effectiveTools == null ? List.of() : List.copyOf(effectiveTools);
    }

    public static SubagentToolPolicy empty() {
        return new SubagentToolPolicy(List.of(), List.of());
    }
}
