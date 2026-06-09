package cn.lypi.contracts.subagent;

import cn.lypi.contracts.security.PermissionMode;
import java.nio.file.Path;
import java.util.List;

public record HeadlessSubagentInput(
    String childSessionId,
    String parentSessionId,
    String parentSpawnEntryId,
    String prompt,
    Path sessionCwd,
    Path cwd,
    List<String> allowedTools,
    PermissionMode permissionMode,
    int timeoutSeconds
) {
    public HeadlessSubagentInput(
        String childSessionId,
        String parentSessionId,
        String parentSpawnEntryId,
        String prompt,
        Path cwd,
        List<String> allowedTools,
        PermissionMode permissionMode,
        int timeoutSeconds
    ) {
        this(childSessionId, parentSessionId, parentSpawnEntryId, prompt, cwd, cwd, allowedTools, permissionMode, timeoutSeconds);
    }

    public HeadlessSubagentInput {
        sessionCwd = sessionCwd == null ? cwd : sessionCwd;
        cwd = cwd == null ? sessionCwd : cwd;
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }
}
