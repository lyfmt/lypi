package cn.lypi.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class McpToolNameTest {
    @Test
    void formatsClaudeStyleMcpToolName() {
        assertEquals("mcp__github__list_issues", McpToolName.format("github", "list_issues"));
    }

    @Test
    void normalizesIllegalCharacters() {
        assertEquals("mcp__my_server__read_file", McpToolName.format("my server!", "read-file"));
    }
}
