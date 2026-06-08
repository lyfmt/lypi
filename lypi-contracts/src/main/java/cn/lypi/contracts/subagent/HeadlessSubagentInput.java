package cn.lypi.contracts.subagent;

import cn.lypi.contracts.security.PermissionMode;
import java.nio.file.Path;
import java.util.List;

public record HeadlessSubagentInput(
    String childSessionId,
    String parentSessionId,
    String parentSpawnEntryId,
    String prompt,
    Path cwd,
    List<String> allowedTools,
    PermissionMode permissionMode,
    int timeoutSeconds
) {
    public HeadlessSubagentInput {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }
}
