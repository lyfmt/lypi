package cn.lypi.contracts.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
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
    void roundTripKeepsCanonicalIdentityToolAndPermissionFields() throws Exception {
        HeadlessSubagentInput input = new HeadlessSubagentInput(
            "inspect-contracts",
            "agent_01",
            "ses_child",
            "run_01",
            "ses_parent",
            "entry_spawn",
            "检查 contracts",
            Path.of("/tmp/project/.ly-pi"),
            Path.of("/tmp/project"),
            new SubagentToolPolicy(List.of("read"), List.of("read", "grep", "glob")),
            PermissionRuntimeState.fromLegacy(PermissionMode.AUTO),
            30
        );

        String json = mapper.writeValueAsString(input);
        HeadlessSubagentInput restored = mapper.readValue(json, HeadlessSubagentInput.class);

        assertEquals(input, restored);
        assertTrue(json.contains("\"taskName\":\"inspect-contracts\""));
        assertTrue(json.contains("\"runId\":\"run_01\""));
        assertTrue(json.contains("\"permissionRuntimeState\""));
        assertFalse(json.contains("permissionMode"));
        assertFalse(json.contains("runMode"));
        assertFalse(json.contains("allowedTools"));
    }

    @Test
    void rejectsRemovedCompatibilityFields() {
        String json = """
            {
              "taskName": "inspect-contracts",
              "agentId": "agent_01",
              "childSessionId": "ses_child",
              "runId": "run_01",
              "parentSessionId": "ses_parent",
              "parentSpawnEntryId": "entry_spawn",
              "message": "检查 contracts",
              "sessionCwd": "/tmp/project/.ly-pi",
              "cwd": "/tmp/project",
              "toolPolicy": {
                "requestedTools": ["read"],
                "effectiveTools": ["read", "grep", "glob"]
              },
              "permissionRuntimeState": {
                "approvalPolicy": {"mode": "ON_REQUEST"},
                "activePermissionProfile": {"id": ":workspace"},
                "legacyBehavior": {
                  "defaultBashRequiresEscalation": true,
                  "allowExplicitEscalationWithoutPrompt": false,
                  "hardSafetyEnabled": true
                },
                "legacyPermissionMode": "AUTO"
              },
              "timeoutSeconds": 30,
              "runMode": "CONTINUE"
            }
            """;

        assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue(json, HeadlessSubagentInput.class));
    }
}
