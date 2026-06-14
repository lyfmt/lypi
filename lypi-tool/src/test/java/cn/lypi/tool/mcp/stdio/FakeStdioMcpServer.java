package cn.lypi.tool.mcp.stdio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FakeStdioMcpServer {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeStdioMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            JsonNode request = JSON.readTree(line);
            if (!request.has("id")) {
                continue;
            }
            String method = request.path("method").asText();
            Object result = switch (method) {
                case "initialize" -> Map.of(
                    "protocolVersion", "2025-06-18",
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "fake", "version", "1.0.0")
                );
                case "tools/list" -> Map.of("tools", List.of(Map.of(
                    "name", "echo",
                    "description", "Echo text",
                    "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of("text", Map.of("type", "string"))
                    )
                )));
                case "tools/call" -> Map.of(
                    "content", List.of(Map.of(
                        "type", "text",
                        "text", request.path("params").path("arguments").path("text").asText()
                    )),
                    "isError", false
                );
                default -> null;
            };
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", request.path("id").asLong());
            if (result == null) {
                response.put("error", Map.of("code", -32601, "message", "Method not found"));
            } else {
                response.put("result", result);
            }
            System.out.println(JSON.writeValueAsString(response));
            System.out.flush();
        }
    }
}
