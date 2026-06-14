package cn.lypi.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class McpToolResultMapperTest {
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Test
    void mapsTextContentBlocksToNewlineSeparatedText() throws Exception {
        JsonNode result = jsonMapper.readTree("""
            {
              "content": [
                {"type": "text", "text": "line 1"},
                {"type": "text", "text": "line 2"}
              ],
              "isError": false
            }
            """);
        McpToolResultMapper mapper = new McpToolResultMapper(jsonMapper);

        assertEquals("line 1\nline 2", mapper.map(result));
    }

    @Test
    void preservesNonTextContentAsJson() throws Exception {
        JsonNode result = jsonMapper.readTree("""
            {
              "content": [
                {"type": "image", "mimeType": "image/png", "data": "abc"}
              ],
              "isError": false
            }
            """);
        McpToolResultMapper mapper = new McpToolResultMapper(jsonMapper);

        assertTrue(mapper.map(result).contains("\"image\""));
    }

    @Test
    void preservesMcpErrorFlag() throws Exception {
        JsonNode result = jsonMapper.readTree("""
            {
              "content": [
                {"type": "text", "text": "remote failed"}
              ],
              "isError": true
            }
            """);
        McpToolResultMapper mapper = new McpToolResultMapper(jsonMapper);

        McpToolCallResult mapped = mapper.mapResult(result);

        assertEquals("remote failed", mapped.output());
        assertTrue(mapped.error());
    }
}
