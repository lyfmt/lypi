package cn.lypi.contracts.session;

import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

public record SessionHeader(
    String type,
    int version,
    String id,
    Path cwd,
    Optional<String> parentSessionId,
    Instant timestamp,
    Optional<ModelSelection> initialModel,
    Optional<ThinkingLevel> initialThinkingLevel,
    Optional<AgentMode> initialAgentMode,
    Optional<PermissionMode> initialPermissionMode
) {
    public SessionHeader {
        parentSessionId = parentSessionId == null ? Optional.empty() : parentSessionId;
        initialModel = initialModel == null ? Optional.empty() : initialModel;
        initialThinkingLevel = initialThinkingLevel == null ? Optional.empty() : initialThinkingLevel;
        initialAgentMode = initialAgentMode == null ? Optional.empty() : initialAgentMode;
        initialPermissionMode = initialPermissionMode == null ? Optional.empty() : initialPermissionMode;
    }

    public SessionHeader(
        String type,
        int version,
        String id,
        Path cwd,
        Optional<String> parentSessionId,
        Instant timestamp
    ) {
        this(
            type,
            version,
            id,
            cwd,
            parentSessionId,
            timestamp,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }
}
