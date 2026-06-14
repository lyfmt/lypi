package cn.lypi.tool.mcp;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.mcp.McpToolSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将 MCP tools/list 结果映射为 ly-pi 工具 schema。
 */
public final class McpToolSchemaMapper {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper jsonMapper;

    public McpToolSchemaMapper(ObjectMapper jsonMapper) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
    }

    /**
     * 映射指定外部端点返回的 tool schema。
     */
    public List<McpToolSchema> map(String serverName, JsonNode toolsResult) {
        List<McpToolSchema> schemas = new ArrayList<>();
        for (JsonNode tool : toolsResult.path("tools")) {
            String toolName = tool.path("name").asText("");
            schemas.add(new McpToolSchema(
                serverName,
                toolName,
                McpToolName.format(serverName, toolName),
                new JsonSchema(inputSchema(tool.path("inputSchema"))),
                tool.path("description").asText("")
            ));
        }
        return schemas;
    }

    private Map<String, Object> inputSchema(JsonNode inputSchema) {
        if (inputSchema == null || inputSchema.isMissingNode() || inputSchema.isNull()) {
            return Map.of("type", "object");
        }
        return jsonMapper.convertValue(inputSchema, MAP_TYPE);
    }
}
