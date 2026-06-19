package cn.lypi.contracts.subagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.skill.SkillMention;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

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
    PermissionRuntimeState permissionRuntimeState,
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
            PermissionRuntimeState.fromLegacy(permissionMode),
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
            PermissionRuntimeState.fromLegacy(permissionMode),
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
            PermissionRuntimeState.fromLegacy(permissionMode),
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
            PermissionRuntimeState.fromLegacy(permissionMode),
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
        permissionRuntimeState = normalizedPermissionRuntimeState(permissionRuntimeState, null);
        runMode = runMode == null ? HeadlessSubagentRunMode.START : runMode;
        skillMentions = skillMentions == null ? List.of() : List.copyOf(skillMentions);
    }

    @JsonGetter("tools")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public List<String> tools() {
        return toolPolicy.requestedTools();
    }

    /**
     * 返回兼容旧协议的权限模式。
     *
     * NOTE: 新代码应读取 permissionRuntimeState。
     */
    @JsonGetter("permissionMode")
    public PermissionMode permissionMode() {
        return permissionRuntimeState.legacyPermissionMode();
    }

    @JsonCreator
    public static HeadlessSubagentInput create(
        @JsonProperty("childSessionId") String childSessionId,
        @JsonProperty("parentSessionId") String parentSessionId,
        @JsonProperty("parentSpawnEntryId") String parentSpawnEntryId,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("sessionCwd") Path sessionCwd,
        @JsonProperty("cwd") Path cwd,
        @JsonProperty("allowedTools") List<String> allowedTools,
        @JsonProperty("toolPolicy") SubagentToolPolicy toolPolicy,
        @JsonProperty("permissionRuntimeState") PermissionRuntimeState permissionRuntimeState,
        @JsonProperty("permissionMode") PermissionMode permissionMode,
        @JsonProperty("timeoutSeconds") int timeoutSeconds,
        @JsonProperty("runMode") HeadlessSubagentRunMode runMode,
        @JsonProperty("skillMentions") List<SkillMention> skillMentions
    ) {
        return new HeadlessSubagentInput(
            childSessionId,
            parentSessionId,
            parentSpawnEntryId,
            prompt,
            sessionCwd,
            cwd,
            allowedTools,
            toolPolicy,
            normalizedPermissionRuntimeState(permissionRuntimeState, permissionMode),
            timeoutSeconds,
            runMode,
            skillMentions
        );
    }

    private static PermissionRuntimeState normalizedPermissionRuntimeState(
        PermissionRuntimeState permissionRuntimeState,
        PermissionMode permissionMode
    ) {
        if (permissionRuntimeState != null) {
            return permissionRuntimeState;
        }
        return PermissionRuntimeState.fromLegacy(Objects.requireNonNullElse(permissionMode, PermissionMode.DEFAULT_EXECUTE));
    }
}
