package cn.lypi.tool.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.model.ThinkingLevel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SubagentToolInputsTest {
    @Test
    void validatesExactSpawnFieldsAndRequiredStrings() {
        assertTrue(SubagentToolInputs.validateSpawn(Map.of(
            "task_name", "inspect",
            "message", "检查"
        )).valid());
        assertFalse(SubagentToolInputs.validateSpawn(Map.of(
            "taskName", "inspect",
            "message", "检查"
        )).valid());
        assertFalse(SubagentToolInputs.validateSpawn(Map.of(
            "task_name", " ",
            "message", "检查"
        )).valid());
    }

    @Test
    void parsesOnlyCanonicalModelThinkingAndToolValues() {
        Map<String, Object> input = Map.of(
            "provider", "openai",
            "model", "gpt-5.4",
            "thinking_level", "HIGH",
            "tools", List.of("bash", "bash")
        );

        assertEquals(Optional.of("openai"), SubagentToolInputs.optionalString(input, "provider"));
        assertEquals(Optional.of("gpt-5.4"), SubagentToolInputs.optionalString(input, "model"));
        assertEquals(Optional.of(ThinkingLevel.HIGH), SubagentToolInputs.thinkingLevel(input));
        assertEquals(List.of("bash", "bash"), SubagentToolInputs.tools(input));
        assertThrows(IllegalArgumentException.class, () ->
            SubagentToolInputs.thinkingLevel(Map.of("thinking_level", "high"))
        );
        assertThrows(IllegalArgumentException.class, () ->
            SubagentToolInputs.tools(Map.of("tools", "bash"))
        );
    }

    @Test
    void waitTimeoutUsesMillisecondsAndRejectsRemovedAliases() {
        assertEquals(600_000, SubagentToolInputs.timeoutMillis(Map.of()));
        assertEquals(25_000, SubagentToolInputs.timeoutMillis(Map.of("timeout_ms", 25_000)));
        assertTrue(SubagentToolInputs.validateWait(Map.of("timeout_ms", 0)).valid());
        assertFalse(SubagentToolInputs.validateWait(Map.of("timeoutSeconds", 10)).valid());
        assertThrows(IllegalArgumentException.class, () ->
            SubagentToolInputs.timeoutMillis(Map.of("timeout_ms", "10"))
        );
    }
}
