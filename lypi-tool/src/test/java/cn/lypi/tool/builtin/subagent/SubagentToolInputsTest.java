package cn.lypi.tool.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubagentToolInputsTest {
    @TempDir
    Path tempDir;

    @Test
    void requireAnyAcceptsFirstNonBlankAliasAndReportsJoinedNames() {
        ValidationResult valid = SubagentToolInputs.requireAny(Map.of("agent_id", "agent_1"), "agentId", "agent_id");
        ValidationResult invalid = SubagentToolInputs.requireAny(Map.of("agentId", " "), "agentId", "agent_id");

        assertTrue(valid.valid());
        assertFalse(invalid.valid());
        assertEquals(List.of("agentId/agent_id 不能为空。"), invalid.messages());
    }

    @Test
    void readsStringsOptionalStringsIntsAndListsAcrossAliases() {
        Map<String, Object> input = Map.of(
            "name_alias", "worker",
            "empty", "",
            "timeout_seconds", "99",
            "tools", List.of("read", "", "bash"),
            "allowedTools", "grep, glob, "
        );

        assertEquals("worker", SubagentToolInputs.stringInput(input, "name", "name_alias"));
        assertEquals(Optional.empty(), SubagentToolInputs.optionalStringInput(input, "empty"));
        assertEquals(99, SubagentToolInputs.intInput(input, 10, "timeoutSeconds", "timeout_seconds"));
        assertEquals(List.of("read", "bash"), SubagentToolInputs.stringListInput(input, "tools"));
        assertEquals(List.of("grep", "glob"), SubagentToolInputs.stringListInput(input, "allowedTools"));
    }

    @Test
    void timeoutSecondsDefaultsAndClampsToSupportedRange() {
        assertEquals(1_200, SubagentToolInputs.timeoutSeconds(Map.of()));
        assertEquals(1, SubagentToolInputs.timeoutSeconds(Map.of("timeoutSeconds", -5)));
        assertEquals(1_200, SubagentToolInputs.timeoutSeconds(Map.of("timeout_seconds", 9_999)));
    }

    @Test
    void parsesPermissionModeAndAgentModeWithFriendlyAliases() {
        assertEquals(PermissionMode.ASK, SubagentToolInputs.permissionMode(Map.of()));
        assertEquals(PermissionMode.ASK, SubagentToolInputs.permissionMode(Map.of("permissionMode", "useDefault")));
        assertEquals(PermissionMode.AUTO, SubagentToolInputs.permissionMode(Map.of("permission_mode", "accept-edits")));
        assertEquals(Optional.of(AgentMode.EXECUTE), SubagentToolInputs.agentMode(Map.of("mode", "general")));
        assertEquals(Optional.of(AgentMode.PLAN), SubagentToolInputs.agentMode(Map.of("agentMode", "plan")));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            SubagentToolInputs.permissionMode(Map.of("permissionMode", "use-default-now"))
        );
        assertTrue(exception.getMessage().contains("ask, auto, bypass"));
        assertFalse(exception.getMessage().contains("No enum constant"));
    }

    @Test
    void parsesModelThinkingLevelToolPolicyAndCwd() {
        Map<String, Object> input = Map.of(
            "model", "custom/gpt-5.4",
            "thinkingLevel", "high",
            "tools", List.of("read", "bash", "read"),
            "allowedTools", List.of("grep", "bash"),
            "cwd", "nested"
        );
        ToolUseContext context = context();
        SubagentToolPolicy policy = SubagentToolInputs.toolPolicy(input);

        assertEquals(Optional.of(ThinkingLevel.HIGH), SubagentToolInputs.thinkingLevel(input));
        assertEquals(Optional.of(new ModelSelection("custom", "gpt-5.4", ThinkingLevel.HIGH)), SubagentToolInputs.model(input));
        assertEquals(List.of("read", "bash", "grep"), policy.requestedTools());
        assertEquals(List.of("read", "grep", "glob", "bash"), policy.effectiveTools());
        assertEquals(tempDir.resolve("nested").toAbsolutePath().normalize(), SubagentToolInputs.cwd(input, context));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            SubagentToolInputs.cwd(Map.of("cwd", "../outside"), context)
        );
        assertTrue(exception.getMessage().contains("cwd 越过当前工作目录"));
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of());
    }
}
