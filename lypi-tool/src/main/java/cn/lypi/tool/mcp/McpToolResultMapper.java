package cn.lypi.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将 MCP tools/call 结果映射为上下文可读文本。
 */
public final class McpToolResultMapper {
    private final ObjectMapper jsonMapper;

    public McpToolResultMapper(ObjectMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    }

    /**
     * 返回 MCP tool 结果的人类可读表示。
     */
    public String map(JsonNode result) {
        JsonNode content = result.path("content");
        if (!content.isArray()) {
            return compact(result);
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                parts.add(block.path("text").asText(""));
            } else {
                parts.add(compact(block));
            }
        }
        return String.join("\n", parts);
    }

    private String compact(JsonNode node) {
        try {
            return jsonMapper.writeValueAsString(node);
        } catch (Exception exception) {
            return String.valueOf(node);
        }
    }
}
