package cn.lypi.contracts.subagent;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SubagentContinueRequest(
    String parentSessionId,
    String parentEntryId,
    String childSessionId,
    String prompt,
    Path cwd,
    List<String> allowedTools,
    SubagentToolPolicy toolPolicy,
    PermissionMode permissionMode,
    int timeoutSeconds,
    Optional<ModelSelection> model,
    Optional<ThinkingLevel> thinkingLevel,
    Optional<AgentMode> agentMode
) {
    public SubagentContinueRequest {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        toolPolicy = toolPolicy == null ? new SubagentToolPolicy(allowedTools, allowedTools) : toolPolicy;
        permissionMode = permissionMode == null ? PermissionMode.DEFAULT_EXECUTE : permissionMode;
        model = model == null ? Optional.empty() : model;
        thinkingLevel = thinkingLevel == null ? Optional.empty() : thinkingLevel;
        agentMode = agentMode == null ? Optional.empty() : agentMode;
    }

    public SubagentContinueRequest(
        String parentSessionId,
        String parentEntryId,
        String childSessionId,
        String prompt,
        Path cwd,
        List<String> allowedTools,
        int timeoutSeconds
    ) {
        this(
            parentSessionId,
            parentEntryId,
            childSessionId,
            prompt,
            cwd,
            allowedTools,
            new SubagentToolPolicy(allowedTools, allowedTools),
            PermissionMode.DEFAULT_EXECUTE,
            timeoutSeconds,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    public SubagentContinueRequest(
        String childSessionId,
        String prompt,
        List<String> tools,
        int timeoutSeconds
    ) {
        this(
            null,
            null,
            childSessionId,
            prompt,
            null,
            tools,
            new SubagentToolPolicy(tools, tools),
            PermissionMode.DEFAULT_EXECUTE,
            timeoutSeconds,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    @JsonGetter("tools")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public List<String> tools() {
        return toolPolicy.requestedTools();
    }
}
