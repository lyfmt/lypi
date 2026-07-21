package cn.lypi.contracts.subagent;

import cn.lypi.contracts.security.PermissionRuntimeState;
import java.nio.file.Path;

public record HeadlessSubagentInput(
    String taskName,
    String agentId,
    String childSessionId,
    String runId,
    String parentSessionId,
    String parentSpawnEntryId,
    String message,
    Path sessionCwd,
    Path cwd,
    SubagentToolPolicy toolPolicy,
    PermissionRuntimeState permissionRuntimeState,
    int timeoutSeconds
) {
    public HeadlessSubagentInput {
        sessionCwd = sessionCwd == null ? cwd : sessionCwd;
        cwd = cwd == null ? sessionCwd : cwd;
        toolPolicy = toolPolicy == null ? SubagentToolPolicy.empty() : toolPolicy;
        timeoutSeconds = timeoutSeconds <= 0 ? 600 : timeoutSeconds;
    }
}
