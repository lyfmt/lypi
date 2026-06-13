package cn.lypi.contracts.subagent;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.file.Path;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SubagentContinueRequest(
    String parentSessionId,
    String parentEntryId,
    String childSessionId,
    String prompt,
    Path cwd,
    List<String> allowedTools,
    SubagentToolPolicy toolPolicy,
    int timeoutSeconds
) {
    public SubagentContinueRequest {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        toolPolicy = toolPolicy == null ? new SubagentToolPolicy(allowedTools, allowedTools) : toolPolicy;
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
            timeoutSeconds
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
            timeoutSeconds
        );
    }

    @JsonGetter("tools")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public List<String> tools() {
        return toolPolicy.requestedTools();
    }
}
