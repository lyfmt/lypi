package cn.lypi.tool.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.model.ThinkingLevel;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubagentToolSchemasTest {
    @Test
    void exposesCanonicalThinkingAndMillisecondTimeoutSchemas() {
        Map<String, Object> thinking = SubagentToolSchemas.thinkingLevelSchema();
        Map<String, Object> timeout = SubagentToolSchemas.timeoutMillisSchema();

        assertEquals(
            Arrays.stream(ThinkingLevel.values()).map(Enum::name).toList(),
            thinking.get("enum")
        );
        assertEquals("integer", timeout.get("type"));
        assertEquals(0L, timeout.get("minimum"));
        assertEquals(3_600_000L, timeout.get("maximum"));
        assertTrue(thinking.get("description").toString().contains("继承"));
    }
}
