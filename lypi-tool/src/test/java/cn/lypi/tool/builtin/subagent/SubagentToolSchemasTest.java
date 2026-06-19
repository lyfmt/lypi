package cn.lypi.tool.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubagentToolSchemasTest {
    @Test
    void exposesSharedSchemaFragmentsForSubagentTools() {
        Map<String, Object> timeout = SubagentToolSchemas.timeoutSecondsSchema();
        Map<String, Object> permissionMode = SubagentToolSchemas.permissionModeSchema();
        Map<String, Object> permissionRuntimeState = SubagentToolSchemas.permissionRuntimeStateSchema();
        Map<String, Object> agentMode = SubagentToolSchemas.agentModeSchema();
        Map<String, Object> thinkingLevel = SubagentToolSchemas.thinkingLevelSchema();
        Map<String, Object> model = SubagentToolSchemas.modelSchema();

        assertEquals("integer", timeout.get("type"));
        assertEquals(1, timeout.get("minimum"));
        assertEquals(1_200, timeout.get("maximum"));
        assertEquals(List.of("DEFAULT_EXECUTE", "ACCEPT_EDITS", "BYPASS"), permissionMode.get("enum"));
        assertEquals(List.of("PLAN", "EXECUTE"), agentMode.get("enum"));
        assertEquals(List.of("LOW", "MEDIUM", "HIGH", "MAX"), thinkingLevel.get("enum"));
        assertTrue(permissionMode.get("description").toString().contains("useDefault"));
        assertTrue(permissionMode.get("description").toString().contains("legacy"));
        assertTrue(permissionRuntimeState.get("description").toString().contains("canonical"));
        assertTrue(permissionRuntimeState.get("description").toString().contains("approvalPolicy"));
        assertTrue(permissionRuntimeState.get("description").toString().contains("activePermissionProfile"));
        assertTrue(permissionRuntimeState.get("description").toString().contains("permissionMode"));
        assertTrue(agentMode.get("description").toString().contains("general"));
        assertTrue(model.get("description").toString().contains("继承父 session"));
    }
}
