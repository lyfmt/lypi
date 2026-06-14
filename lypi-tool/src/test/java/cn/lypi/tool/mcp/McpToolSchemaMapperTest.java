package cn.lypi.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lypi.contracts.mcp.McpToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpToolSchemaMapperTest {
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Test
    void mapsMcpToolsListResultToLyPiSchemas() throws Exception {
        JsonNode result = jsonMapper.readTree("""
            {
              "tools": [{
                "name": "search_files",
                "description": "Search files",
                "inputSchema": {
                  "type": "object",
                  "properties": {"query": {"type": "string"}},
                  "required": ["query"]
                }
              }]
            }
            """);
        McpToolSchemaMapper mapper = new McpToolSchemaMapper(jsonMapper);

        List<McpToolSchema> schemas = mapper.map("filesystem", result);

        assertEquals(1, schemas.size());
        McpToolSchema schema = schemas.getFirst();
        assertEquals("filesystem", schema.serverName());
        assertEquals("search_files", schema.toolName());
        assertEquals("mcp__filesystem__search_files", schema.lyPiToolName());
        assertEquals("Search files", schema.description());
        assertEquals("object", schema.inputSchema().value().get("type"));
    }
}
