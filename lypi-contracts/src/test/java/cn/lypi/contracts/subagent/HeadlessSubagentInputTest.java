package cn.lypi.contracts.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.skill.SkillMention;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeadlessSubagentInputTest {
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .registerModule(new JavaTimeModule());

    @Test
    void deserializesLegacyPermissionModeIntoCanonicalRuntimeState() throws Exception {
        String json = """
            {
              "childSessionId": "ses_child",
              "parentSessionId": "ses_parent",
              "parentSpawnEntryId": "entry_spawn",
              "prompt": "继续检查",
              "sessionCwd": "/tmp/project/.ly-pi",
              "cwd": "/tmp/project",
              "allowedTools": ["read"],
              "toolPolicy": {
                "requestedTools": ["read"],
                "effectiveTools": ["read", "grep"]
              },
              "permissionMode": "ACCEPT_EDITS",
              "timeoutSeconds": 30,
              "runMode": "CONTINUE"
            }
            """;

        HeadlessSubagentInput restored = mapper.readValue(json, HeadlessSubagentInput.class);

        assertEquals(PermissionRuntimeState.fromLegacy(PermissionMode.ACCEPT_EDITS), restored.permissionRuntimeState());
        assertEquals(PermissionMode.ACCEPT_EDITS, restored.permissionMode());
    }

    @Test
    void serializesCanonicalPermissionRuntimeStateAndKeepsCompatibilityAccessor() throws Exception {
        HeadlessSubagentInput input = new HeadlessSubagentInput(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "继续检查",
            Path.of("/tmp/project/.ly-pi"),
            Path.of("/tmp/project"),
            List.of("read"),
            new SubagentToolPolicy(List.of("read"), List.of("read", "grep")),
            PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE),
            30,
            HeadlessSubagentRunMode.CONTINUE,
            List.<SkillMention>of()
        );

        String json = mapper.writeValueAsString(input);
        HeadlessSubagentInput restored = mapper.readValue(json, HeadlessSubagentInput.class);

        assertTrue(json.contains("\"permissionRuntimeState\""));
        assertTrue(json.contains("\"permissionMode\":\"DEFAULT_EXECUTE\""));
        assertEquals(input.permissionRuntimeState(), restored.permissionRuntimeState());
        assertEquals(PermissionMode.DEFAULT_EXECUTE, restored.permissionMode());
    }

    @Test
    void prefersCanonicalPermissionRuntimeStateWhenLegacyFieldAlsoExists() throws Exception {
        String json = """
            {
              "childSessionId": "ses_child",
              "parentSessionId": "ses_parent",
              "parentSpawnEntryId": "entry_spawn",
              "prompt": "继续检查",
              "sessionCwd": "/tmp/project/.ly-pi",
              "cwd": "/tmp/project",
              "allowedTools": ["read"],
              "toolPolicy": {
                "requestedTools": ["read"],
                "effectiveTools": ["read", "grep"]
              },
              "permissionMode": "DEFAULT_EXECUTE",
              "permissionRuntimeState": {
                "approvalPolicy": {
                  "mode": "NEVER"
                },
                "activePermissionProfile": {
                  "id": ":danger-full-access"
                },
                "legacyBehavior": {
                  "defaultBashRequiresEscalation": false,
                  "allowExplicitEscalationWithoutPrompt": true,
                  "hardSafetyEnabled": true
                },
                "legacyPermissionMode": "BYPASS"
              },
              "timeoutSeconds": 30,
              "runMode": "CONTINUE"
            }
            """;

        HeadlessSubagentInput restored = mapper.readValue(json, HeadlessSubagentInput.class);

        assertEquals(PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS), restored.permissionRuntimeState());
        assertEquals(PermissionMode.BYPASS, restored.permissionMode());
        assertFalse(restored.permissionRuntimeState().legacyBehavior().defaultBashRequiresEscalation());
    }
}
