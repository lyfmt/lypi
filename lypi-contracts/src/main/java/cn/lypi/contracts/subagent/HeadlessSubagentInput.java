package cn.lypi.contracts.subagent;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.skill.SkillMention;
import java.nio.file.Path;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HeadlessSubagentInput(
    String childSessionId,
    String parentSessionId,
    String parentSpawnEntryId,
    String prompt,
    Path sessionCwd,
    Path cwd,
    List<String> allowedTools,
    SubagentToolPolicy toolPolicy,
    PermissionMode permissionMode,
    int timeoutSeconds,
    HeadlessSubagentRunMode runMode,
    List<SkillMention> skillMentions
) {
    public HeadlessSubagentInput(
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
        this(
            childSessionId,
            parentSessionId,
            parentSpawnEntryId,
            prompt,
            sessionCwd,
            cwd,
            allowedTools,
            new SubagentToolPolicy(allowedTools, allowedTools),
            permissionMode,
            timeoutSeconds,
            HeadlessSubagentRunMode.START,
            List.of()
        );
    }

    public HeadlessSubagentInput(
        String childSessionId,
        String parentSessionId,
        String parentSpawnEntryId,
        String prompt,
        Path sessionCwd,
        Path cwd,
        SubagentToolPolicy toolPolicy,
        PermissionMode permissionMode,
        int timeoutSeconds,
        HeadlessSubagentRunMode runMode
    ) {
        this(
            childSessionId,
            parentSessionId,
            parentSpawnEntryId,
            prompt,
            sessionCwd,
            cwd,
            toolPolicy == null ? List.of() : toolPolicy.requestedTools(),
            toolPolicy,
            permissionMode,
            timeoutSeconds,
            runMode,
            List.of()
        );
    }

    public HeadlessSubagentInput(
        String childSessionId,
        String parentSessionId,
        String parentSpawnEntryId,
        String prompt,
        Path sessionCwd,
        Path cwd,
        SubagentToolPolicy toolPolicy,
        PermissionMode permissionMode,
        int timeoutSeconds,
        HeadlessSubagentRunMode runMode,
        List<SkillMention> skillMentions
    ) {
        this(
            childSessionId,
            parentSessionId,
            parentSpawnEntryId,
            prompt,
            sessionCwd,
            cwd,
            toolPolicy == null ? List.of() : toolPolicy.requestedTools(),
            toolPolicy,
            permissionMode,
            timeoutSeconds,
            runMode,
            skillMentions
        );
    }

    public HeadlessSubagentInput(
        String childSessionId,
        String parentSessionId,
        String parentSpawnEntryId,
        String prompt,
        Path sessionCwd,
        Path cwd,
        List<String> allowedTools,
        SubagentToolPolicy toolPolicy,
        PermissionMode permissionMode,
        int timeoutSeconds,
        HeadlessSubagentRunMode runMode
    ) {
        this(
            childSessionId,
            parentSessionId,
            parentSpawnEntryId,
            prompt,
            sessionCwd,
            cwd,
            allowedTools,
            toolPolicy,
            permissionMode,
            timeoutSeconds,
            runMode,
            List.of()
        );
    }

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
        toolPolicy = toolPolicy == null ? new SubagentToolPolicy(allowedTools, allowedTools) : toolPolicy;
        runMode = runMode == null ? HeadlessSubagentRunMode.START : runMode;
        skillMentions = skillMentions == null ? List.of() : List.copyOf(skillMentions);
    }

    @JsonGetter("tools")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public List<String> tools() {
        return toolPolicy.requestedTools();
    }
}
