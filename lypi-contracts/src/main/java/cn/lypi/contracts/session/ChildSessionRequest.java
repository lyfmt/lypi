package cn.lypi.contracts.session;

import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import java.nio.file.Path;
import java.util.Optional;

public record ChildSessionRequest(
    String childSessionId,
    String parentSessionId,
    String parentSpawnEntryId,
    Path sessionCwd,
    Path cwd,
    int depth,
    Optional<String> agentName,
    Optional<String> agentRole,
    Optional<ModelSelection> initialModel,
    Optional<ThinkingLevel> initialThinkingLevel,
    Optional<AgentMode> initialAgentMode,
    Optional<PermissionMode> initialPermissionMode,
    SubagentToolPolicy toolPolicy
) {
    public ChildSessionRequest(
        String childSessionId,
        String parentSessionId,
        String parentSpawnEntryId,
        Path sessionCwd,
        Path cwd,
        int depth,
        Optional<String> agentName,
        Optional<String> agentRole
    ) {
        this(
            childSessionId,
            parentSessionId,
            parentSpawnEntryId,
            sessionCwd,
            cwd,
            depth,
            agentName,
            agentRole,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            SubagentToolPolicy.empty()
        );
    }

    public ChildSessionRequest(
        String childSessionId,
        String parentSessionId,
        String parentSpawnEntryId,
        Path cwd,
        int depth,
        Optional<String> agentName,
        Optional<String> agentRole
    ) {
        this(childSessionId, parentSessionId, parentSpawnEntryId, cwd, cwd, depth, agentName, agentRole);
    }

    public ChildSessionRequest {
        sessionCwd = sessionCwd == null ? cwd : sessionCwd;
        cwd = cwd == null ? sessionCwd : cwd;
        agentName = agentName == null ? Optional.empty() : agentName;
        agentRole = agentRole == null ? Optional.empty() : agentRole;
        initialModel = initialModel == null ? Optional.empty() : initialModel;
        initialThinkingLevel = initialThinkingLevel == null ? Optional.empty() : initialThinkingLevel;
        initialAgentMode = initialAgentMode == null ? Optional.empty() : initialAgentMode;
        initialPermissionMode = initialPermissionMode == null ? Optional.empty() : initialPermissionMode;
        toolPolicy = toolPolicy == null ? SubagentToolPolicy.empty() : toolPolicy;
    }
}
